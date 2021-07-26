# GotoHotel
AWS_Intensive Coursework 3차수 Personal Project

---

# Table of contents

* **호텔 예약**
  - 서비스 시나리오
  - 분석/설계
  - 구현
    + 포트넘버 
    + DDD의 적용
    + 폴리글랏 퍼시스턴스
    + 폴리글랏 프로그래밍
    + Saga
    + CQRS
    + Correlation
    + 동기식 호출과 Fallback 처리
    + API Gateway
    + 비동기식 호출과 Eventual Consistency   
 
  - 운영
    + 컨테이너 이미지 생성 및 배포
    + 동기식 호출
    + Circuit Breaker
    + Autoscale (HPA)
    + Configmap or Persistence Volume
    + Polyglot
    + Zero-downtime deploy (Readiness Probe)
    + Self Healing(Liveness Probe)

# 시나리오
GotoHetel 예약 시스템에서 요구하는 기능/비기능 요구사항은 다음과 같습니다.   
사용자가 호텔 룸타입을 고르고 예약 및 결제를 진행하면 호텔 관리자가 예약을 확정하는 시스템입니다.   
사용자는 Mypage에서 예약 진행 상황을 확인할 수 있고, 카카오톡으로 예약상태 정보를 받을 수 있습니다.   

* **기능적 요구사항**   
1. 고객이 원하는 객실을 선택하여 예약한다.
2. 고객인 예약한 객실에 대해 결제 한다.
3. 호텔에서는 결제가 완료된 예약 정보를 받는다.
4. 호텔은 예약 정보를 확인하여 예약을 확정한다.
5. 확정된 예약 정보는 카카오톡으로 고객에게 알림된다.
6. 고객이 예약 신청을 취소할 수 있다.
7. 고객이 취소한 예약은 호텔에서 취소 처리 및 결제 취소한다.
8. 고객이 예약 진행 상황을 마이페이지에서 조회 가능하다.
9. 예약 상태 변경 시 카카오톡으로 고객에게 알림을 보낸다.   

* **비기능적 요구사항**   
1. 트랜잭션
    - 결제가 되지 않은 예약건은 아예 호텔 예약 신청이 되지 않아야 한다. (Sync 호출)
    - 예약이 취소되면 결제도 취소가 되어야 한다. (Req/Res)

2. 장애격리
    - 예약 확정 관리자 기능이 수행 되지 않더라도 예약신청은 365일 24시간 받을 수 있어야 한다. (Pub/Sub, Eventual consistency)
    - 예약 시스템이 과중 되면 사용자를 잠시 동안 받지 않고 결제를 잠시후에 하도록 유도한다. (Circuit Breaker, Fallback) 

3. 성능   
    - 고객이 예약 확인 상태를 마이페이지에서 확인할 수 있어야 한다. (CQRS)   
    - 예약 상태가 바뀔 때 마다 카카오톡 알림이 발송되어야 한다. (Event driven)

# 분석/설계

**1. Event Storming 모델**

![ESM]()

**2. 헥사고날 아키텍처 다이어그램 도출**

![Kafka](https://github.com/bbibbo55/GotoHotel/blob/main/kafka.png)

# 구현

분석/설계 단계에서 도출된 헥사고날 아키텍처를 적용하여 각 BC별도 대변되는 마이크로서비스들을 Spring-boot 로 구현한다.   
각 서비스 별로 부여된 포트넘버를 확인한다. (8001 ~ 8004)

**포트넘버 분리**

```C
spring:
  profiles: default
  cloud:
    gateway:
      routes:
        - id: order
          uri: http://localhost:8081
          predicates:
            - Path=/orders/** 
        - id: reservation
          uri: http://localhost:8082
          predicates:
            - Path=/reservations/** 
        - id: pay
          uri: http://localhost:8083
          predicates:
            - Path=/payments/** 
        - id: customerCenter
          uri: http://localhost:8084
          predicates:
            - Path= /mypages/**
      globalcors:
        corsConfigurations:
```
**각 서비스 수행**

```
]root@labs-1603723474:/home/project/order# ls
Dockerfile  azure-pipelines.yml  cloudbuild.yaml  kubernetes  pom.xml  src  target
]root@labs-1603723474:/home/project/order# mvn spring-boot:run

]root@labs-1603723474:/home/project/pay# ls
Dockerfile  azure-pipelines.yml  cloudbuild.yaml  kubernetes  pom.xml  src  target
]root@labs-1603723474:/home/project/pay# mvn spring-boot:run

]root@labs-1603723474:/home/project/reservation# ls
Dockerfile  azure-pipelines.yml  cloudbuild.yaml  kubernetes  pom.xml  src  target
]root@labs-1603723474:/home/project/reservation# mvn spring-boot:run

]root@labs-1603723474:/home/project/customerCenter# ls
Dockerfile  azure-pipelines.yml  cloudbuild.yaml  kubernetes  pom.xml  src  target
]root@labs-1603723474:/home/project/customerCenter# mvn spring-boot:run

```

**DDD 적용**
  - 각 서비스 내에 도출된 핵심 Aggregate Root 객체를 Entity로 선언하였다.
  - order 마이크로서비스를 예로 들어본다.
```
package gotohotel;

import javax.persistence.*;
import org.springframework.beans.BeanUtils;

import gotohotel.external.Payment;

import java.util.List;
import java.util.Date;

@Entity
@Table(name="Order_table")
public class Order {

    @Id
    @GeneratedValue(strategy=GenerationType.AUTO)
    private Long id;
    private String orderId;
    private String name;
    private String roomType;
    private Integer guestCnt;
    private String status;
    private Long cardNo;

    @PostPersist
    public void onPostPersist(){
        Ordered ordered = new Ordered();
        BeanUtils.copyProperties(this, ordered);
        ordered.publishAfterCommit();

        //Following code causes dependency to external APIs
        // it is NOT A GOOD PRACTICE. instead, Event-Policy mapping is recommended.

    //    gotohotel.external.Payment payment = new gotohotel.external.Payment();
        Payment payment = new Payment();
        payment.setOrderId(this.id);
        // mappings goes here
        OrderApplication.applicationContext.getBean(gotohotel.external.PaymentService.class)
            .processPayment(payment);

    }
    @PrePersist
    public void onPrePersist(){
    }
    @PreRemove
    public void onPreRemove(){
        OrderCanceled orderCanceled = new OrderCanceled();
        BeanUtils.copyProperties(this, orderCanceled);
        orderCanceled.publishAfterCommit();

        //Following code causes dependency to external APIs
        // it is NOT A GOOD PRACTICE. instead, Event-Policy mapping is recommended.

        gotohotel.external.Reservation reservation = new gotohotel.external.Reservation();
        // mappings goes here
        OrderApplication.applicationContext.getBean(gotohotel.external.ReservationService.class)
            .cancelReserve(reservation);

    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }
    
    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRoomType() {
        return roomType;
    }

    public void setRoomType(String roomType) {
        this.roomType = roomType;
    }

    public Integer getGuestCnt() {
        return guestCnt;
    }

    public void setGuestCnt(Integer guestCnt) {
        this.guestCnt = guestCnt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getCardNo() {
        return cardNo;
    }

    public void setCardNo(Long cardNo) {
        this.cardNo = cardNo;
    }

}

```
  - REST API 테스트

```
# order 서비스 주문처리

```
