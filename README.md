# GotoHotel
AWS_Intensive Coursework 3차수 Personal Project

---

# Table of contents

* **GotoHetel 예약**
  - 서비스 시나리오
  - 분석/설계
  - 구현
    + 포트넘버 분리
    + DDD의 적용
    + 폴리글랏 퍼시스턴스
    + Saga
    + CQRS
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

![ESM](https://github.com/bbibbo55/GotoHotel/blob/main/ESModel.PNG)

**2. 헥사고날 아키텍처 다이어그램 도출**

![Kafka]()

# 구현

분석/설계 단계에서 도출된 헥사고날 아키텍처를 적용하여 각 BC별도 대변되는 마이크로서비스들을 Spring-boot 로 구현한다.   
각 서비스 별로 부여된 포트넘버를 확인한다. (8001 ~ 8004)

* **포트넘버 분리**

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
 
* **DDD 적용**
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
http localhost:8081/orders name=Moonhee roomType=delux

# pay 서비스 결제처리
http localhost:8083/payments orderId=1 cardNo=12345

# 선택한 room order에 대한 예약처리
http localhost:8082/reservations orderId=1 status="confirmed"

# 주문 상태 확인
http localhost:8081/orders/3

HTTP/1.1 200 
Content-Type: application/hal+json;charset=UTF-8
Date: Mon, 26 Jul 2021 08:10:29 GMT
Transfer-Encoding: chunked

{
    "_links": {
        "order": {
            "href": "http://localhost:8081/orders/1"
        },
        "self": {
            "href": "http://localhost:8081/orders/1"
        }
    },
    "name: "Moonhee",
    "roomType": "delux",
    "status": "confirmed"
    "guestCnt": null,
    "cardNo": null,
}
```

* **폴리글랏 퍼시스턴스**

비지니스 로직은 내부에 순수한 형태로 구현하며,   
그 이외의 것을 어댑터 형식으로 설계하여 해당 비지니스 로직이 어떤 환경에서도 잘 동작하도록 설계한다.   

폴리그랏 퍼시스턴스 조건을 만족하기 위해 기존 h2 DB를 hsqldb로 변경하여 동작시킨다.

```
<!--	<dependency> -->
<!--		<groupId>com.h2database</groupId> -->
<!--		<artifactId>h2</artifactId> -->
<!--		<scope>runtime</scope> -->
<!--	</dependency> -->

		  <dependency>
			  <groupId>org.hsqldb</groupId>
			  <artifactId>hsqldb</artifactId>
			  <version>2.4.0</version>
			  <scope>runtime</scope>
		  </dependency>
```

**reservation의 pom.yml 파일 내 DB 정보 변경 및 재기동 후 예약 처리**

```
]root@labs-1603723474:/home/project# http localhost:8082/reservations orderId=2 status=DBchanged
HTTP/1.1 201 
Content-Type: application/json;charset=UTF-8
Date: Mon, 26 Jul 2021 08:42:05 GMT
Location: http://localhost:8082/reservations/1
Transfer-Encoding: chunked

{
    "_links": {
        "reservation": {
            "href": "http://localhost:8082/reservations/1"
        },
        "self": {
            "href": "http://localhost:8082/reservations/1"
        }
    },
    "orderId": 2,
    "status": "DBchanged"
}
```

**Mypage에서 예약이 잘 되었는지 조회**
    
```
]root@labs-1603723474:/home/project# http localhost:8084/mypages/2
HTTP/1.1 200 
Content-Type: application/hal+json;charset=UTF-8
Date: Mon, 26 Jul 2021 08:43:04 GMT
Transfer-Encoding: chunked

{
    "_links": {
        "mypage": {
            "href": "http://localhost:8084/mypages/2"
        },
        "self": {
            "href": "http://localhost:8084/mypages/2"
        }
    },
    "guestCnt": null,
    "name": null,
    "orderId": 2,
    "roomType": "delux",
    "status": "DBchanged"
}
```
* **CQRS(마이페이지)**

고객이 예약한 건에 대해 예약/결제 상태를 조회할 수 있도록 CQRS로 구현하였으며,   
Mypage를 통해 모든 예약건에 대한 등록/변경 상태정보를 확인 할 수 잇다. 

```
# 예약 상태를 MyPage 호출하여 확인

]root@labs-1603723474:/home/project# http localhost:8084/mypages/
HTTP/1.1 200 
Content-Type: application/hal+json;charset=UTF-8
Date: Mon, 26 Jul 2021 09:01:21 GMT
Transfer-Encoding: chunked

{
    "_embedded": {
        "mypages": [
            {
                "_links": {
                    "mypage": {
                        "href": "http://localhost:8084/mypages/1"
                    },
                    "self": {
                        "href": "http://localhost:8084/mypages/1"
                    }
                },
                "guestCnt": null,
                "name": "Moonhee",
                "orderId": 1,
                "roomType": null,
                "status": "confirmed"
            },
            {
                "_links": {
                    "mypage": {
                        "href": "http://localhost:8084/mypages/2"
                    },
                    "self": {
                        "href": "http://localhost:8084/mypages/2"
                    }
                },
                "guestCnt": 2,
                "name": HaHa,
                "orderId": 2,
                "roomType": "delux",
                "status": "DBchanged"
            },
            {
                "_links": {
                    "mypage": {
                        "href": "http://localhost:8084/mypages/3"
                    },
                    "self": {
                        "href": "http://localhost:8084/mypages/3"
                    }
                },
                "guestCnt": 2,
                "name": "Woojin",
                "orderId": 3,
                "roomType": "childRoom",
                "status": "Complete"
            }
        ]
    },
    "_links": {
        "profile": {
            "href": "http://localhost:8084/profile/mypages"
        },
        "search": {
            "href": "http://localhost:8084/mypages/search"
        },
        "self": {
            "href": "http://localhost:8084/mypages/"
        }
    }
}
```

* **동기식 호출과 Fallback처리**

분석단계의 조건 중 하나로, 예약주문 시 주문과 결제 처리를 동기식으로 처리하는 요구사항을 만족한다. 
주문(Order)->결제(Pay) 간의 호출은 동기식 일관성을 유지하는 트랜잭션으로 처리하며,   
호출 프로토콜은 REST Repositor에 의해 노출되어 있는 REST 서비스를 FeignClient를 이용하여 호출한다.   

##### 결제서비스를 호출하기 위해 FeignClient를 이용하여 Service 대행 인터페이스(Proxy)를 구현

```
#(external) PaymentService.java

package gotohotel.external;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;


@FeignClient(name="pay", url="${api.payment.url}")
public interface PaymentService {
    @RequestMapping(method= RequestMethod.POST, path="/payments")
    public void processPayment(@RequestBody Payment payment);

}
```
##### 주문 받은 직후(@PostPersist) 결제를 요청하여 처리

```
# Order.java (Entity)
    @PostPersist
    public void onPostPersist(){
        Ordered ordered = new Ordered();
        BeanUtils.copyProperties(this, ordered);
        ordered.publishAfterCommit();

        gotohotel.external.Payment payment = new gotohotel.external.Payment();
        System.out.println("this.id() : " + this.id);
        payment.setOrderId(this.id);
        payment.setStatus("Reservation OK");
        OrderApplication.applicationContext.getBean(gotohotel.external.PaymentService.class)
            .processPayment(payment);

    }

```

##### 동기식 호출에서는 호출 시간에 따른 타임 커플링이 발생하며, 결제 시스템이 장애가 나면 주문을 받을 수 없음을 확인
```
# 결제(payment) 서비스를 잠시 내려놓음

# 주문처리
]root@labs-16037http localhost:8081/orders name=Guest2 roomType=Normal
HTTP/1.1 500 
Connection: close
Content-Type: application/json;charset=UTF-8
Date: Mon, 26 Jul 2021 09:52:02 GMT
Transfer-Encoding: chunked

{
    "error": "Internal Server Error",
    "message": "Could not commit JPA transaction; nested exception is javax.persistence.RollbackException: Error while committing the transaction",
    "path": "/orders",
    "status": 500,
    "timestamp": "2021-07-26T09:52:02.852+0000"
}

#결제서비스 재기동
]root@labs-1603723474:/home/project/pay# mvn spring-boot:run

#주문처리 (정상처리)
]root@labs-1603723474:/home/project# http localhost:8081/orders name=Guest3 roomType=DeluxNormal

HTTP/1.1 200 
Content-Type: application/hal+json;charset=UTF-8
Date: Mon, 26 Jul 2021 09:56:03 GMT
Transfer-Encoding: chunked

{
    "_links": {
        "order": {
            "href": "http://localhost:8081/orders/6"
        },
        "self": {
            "href": "http://localhost:8081/orders/6"
        }
    },
    "name: "Guest3",
    "roomType": "DeluxNormal",
    "status": "null"
    "guestCnt": null,
    "cardNo": null,
}
```

* **API Gateway**

API gateway 를 통해 MSA 진입점을 통일 시킨다.
```
# gateway 기동 (8088 포트)
]root@labs-1603723474:/home/project/gateway# ls
Dockerfile  cloudbuild.yaml  pom.xml  src  target
]root@labs-1603723474:/home/project/gateway# mvn spring-boot:run

# api gateway를 통한 호텔 룸 예약 주문
]root@labs-1603723474:/home/project/# http localhost:8088/orders name=Guest4 roomType=Prime guestCnt=4

HTTP/1.1 200 
Content-Type: application/hal+json;charset=UTF-8
Date: Mon, 26 Jul 2021 10:22:45 GMT
Transfer-Encoding: chunked

{
    "_links": {
        "order": {
            "href": "http://localhost:8088/orders/7"
        },
        "self": {
            "href": "http://localhost:8088/orders/7"
        }
    },
    "name: "Guest4",
    "roomType": "Prime",
    "status": "null"
    "guestCnt": 4,
    "cardNo": null,
}
```

* **비동기식 호출과 Eventual Consistency**

결제가 이루어진 후에 예약 시스템으로 이를 알려주는 행위는 동기식이 아닌 비동기식으로 처리하여(Pub/Sub)   
원활한 예약(Reservation) 서비스 처리를 위하여 주문(Order)/결제(Pay) 처리가 블로킹 되지 않도록 한다.

##### 이러한 처리를 위해 결제 승인이 되었다는 도메인 이벤트를 카프카로 Publish 한다.
```
package gotohotel;

import javax.persistence.*;
import org.springframework.beans.BeanUtils;

@Entity
@Table(name="Payment_table")
public class Payment {

    @Id
    @GeneratedValue(strategy=GenerationType.AUTO)
    private Long id;
    private Long orderId;
    private Long cardNo;
    private String status;

    @PostPersist
    public void onPostPersist(){
        PaymentApproved paymentApproved = new PaymentApproved();
        BeanUtils.copyProperties(this, paymentApproved);
        paymentApproved.publishAfterCommit();

        PaymentCanceled paymentCanceled = new PaymentCanceled();
        BeanUtils.copyProperties(this, paymentCanceled);
        paymentCanceled.publishAfterCommit();

    }
```

##### 예약(reservation)서비스에서는 결제승인 이벤트에 대해서 이를 수신하여 자신의 정책을 처리하도록 PolicyHandler 를 구현한다.

##### 호텔은 예약 상황을 확인 하고, 최종 예약 상태를 시스템에 등록할것이므로 우선 예약정보를 DB에 받아놓은 후, 이후 처리는 해당 Aggregate 처리한다.
```
# (reservation) PolicyHandler.java
package gotohotel;

import gotohotel.config.kafka.KafkaProcessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

@Service
public class PolicyHandler{
    @Autowired ReservationRepository reservationRepository;

    @StreamListener(KafkaProcessor.INPUT)
    public void wheneverPaymentApproved_TakeReserve(@Payload PaymentApproved paymentApproved){

        if(paymentApproved.isMe()){

            System.out.println("\n\n##### listener TakeReserve : " + paymentApproved.toJson() + "\n\n");
            Reservation reservation = new Reservation();
            reservation.setStatus("Reservation Complete");
            reservation.setOrderId(paymentApproved.getOrderId());
            reservationRepository.save(reservation);        

    }
 
 ```
 
