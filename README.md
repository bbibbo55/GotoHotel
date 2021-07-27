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

![Kafka](https://github.com/bbibbo55/GotoHotel/blob/main/kafkamodel.png)

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
 
# 운영

## 컨테이너 이미지 생성 및 배포

**ECR 접속 비밀번호 생성**

```
aws --region "ap-southeast-2" ecr get-login-password
```

**ECR 로그인**

```
docker login --username AWS -p {ECR 접속 비밀번호} 879772956301.dkr.ecr.ap-southeast-2.amazonaws.com
Login Succeeded
```

**마이크로서비스 빌드, order/pay/reservation/custormercenter 각각 실행**

```
mvn clean package -B
```

**컨테이너 이미지 생성**
 - docker build -t 879772956301.dkr.ecr.ap-southeast-2.amazonaws.com/order:v1 .
 - docker build -t 879772956301.dkr.ecr.ap-southeast-2.amazonaws.com/pay:v1 .
 - docker build -t 879772956301.dkr.ecr.ap-southeast-2.amazonaws.com/reservation:v1 .
 - docker build -t 879772956301.dkr.ecr.ap-southeast-2.amazonaws.com/customercenter:v1 .

```
]root@labs-1603723474:/home/project/order# docker build -t 879772956301.dkr.ecr.ap-southeast-2.amazonaws.com/order:v1 .
Sending build context to Docker daemon 59.79 MB
Step 1/4 : FROM openjdk:8u212-jdk-alpine
8u212-jdk-alpine: Pulling from library/openjdk
e7c96db7181b: Pull complete 
f910a506b6cb: Pull complete 
c2274a1a0e27: Pull complete 
Digest: sha256:94792824df2df33402f201713f932b58cb9de94a0cd524164a0f2283343547b3
Status: Downloaded newer image for openjdk:8u212-jdk-alpine
 ---> a3562aa0b991
Step 2/4 : COPY target/*SNAPSHOT.jar app.jar
 ---> 6c5de7599f8c
Step 3/4 : EXPOSE 8080
 ---> Running in 69d162c84a8e
Removing intermediate container 69d162c84a8e
 ---> 4549d977d0bd
Step 4/4 : ENTRYPOINT ["java","-Xmx400M","-Djava.security.egd=file:/dev/./urandom","-jar","/app.jar","--spring.profiles.active=docker"]
 ---> Running in 414d063d1c7a
Removing intermediate container 414d063d1c7a
 ---> 436b7f022620
Successfully built 436b7f022620
Successfully tagged 879772956301.dkr.ecr.ap-southeast-2.amazonaws.com/order:v1

```
```
]root@labs-1603723474:/home/project/customerCenter# docker images
REPOSITORY                                                         TAG                 IMAGE ID            CREATED             SIZE
879772956301.dkr.ecr.ap-southeast-2.amazonaws.com/customercenter   v1                  177225fe5d84        46 seconds ago      165 MB
879772956301.dkr.ecr.ap-southeast-2.amazonaws.com/reservation      v1                  539a2d256365        3 minutes ago       165 MB
879772956301.dkr.ecr.ap-southeast-2.amazonaws.com/pay              v1                  cd7a56d23904        4 minutes ago       165 MB
879772956301.dkr.ecr.ap-southeast-2.amazonaws.com/order            v1                  436b7f022620        4 minutes ago       165 MB
openjdk                                                            8u212-jdk-alpine    a3562aa0b991        2 years ago         105 MB
```

**ECR에 컨테이너 이미지 배포**

 - docker push 879772956301.dkr.ecr.ap-southeast-2.amazonaws.com/order:v1
 - docker push 879772956301.dkr.ecr.ap-southeast-2.amazonaws.com/pay:v1
 - docker push 879772956301.dkr.ecr.ap-southeast-2.amazonaws.com/reservation:v1
 - docker push 879772956301.dkr.ecr.ap-southeast-2.amazonaws.com/customercenter:v1
```
]root@labs-1603723474:/home/project/order# docker push 879772956301.dkr.ecr.ap-southeast-2.amazonaws.com/user20-order:v1
The push refers to repository [879772956301.dkr.ecr.ap-southeast-2.amazonaws.com/user20-order]
449732aba075: Pushed 
ceaf9e1ebef5: Pushed 
9b9b7f3d56a0: Pushed 
f1b5933fe4b5: Pushed 
v1: digest: sha256:982b11be031ecade42f47a09d68cb6eb4bec6a013d8aaf3912718b493517f945 size: 1159
```

**네임스페이스 gotohotel 생성 및 이동**

```
kubectl create namespace gotohotel
namespace/gotohotel created

kubectl config set-context --current --namespace=gotohotel
Context "user20@user20-eks.ap-southeast-2.eksctl.io" modified.
```

**EKS에 마이크로서비스 배포, order/payment/reservation/custormerCenter 각각 실행**

```
kubectl create -f deployment.yml 
```

**마이크로서비스 배포 상태 확인**

```
root@labs-1176757103:/home/project/call-taxi/notification# kubectl get all
NAME                                READY   STATUS    RESTARTS   AGE
pod/customercenter-6d8c48fb9f-9lsq7 1/1     Running   0          13s
pod/order-66974757fb-7lgmm          1/1     Running   0          23m
pod/pay-66fbb9bb6b-b75h2            1/1     Running   0          21m
pod/reservation-68dc8c44b6-kntqt    1/1     Running   0          21m


NAME                 TYPE        CLUSTER-IP   EXTERNAL-IP   PORT(S)   AGE
service/kubernetes   ClusterIP   10.100.0.1   <none>        443/TCP   3h31m

NAME                           READY   UP-TO-DATE   AVAILABLE   AGE
deployment.apps/customercenter 1/1     1            1           14s
deployment.apps/order          1/1     1            1           23m
deployment.apps/pay            1/1     1            1           21m
deployment.apps/reservation    1/1     1            1           21m

NAME                                      DESIRED   CURRENT   READY   AGE
replicaset.apps/customercenter-6d8c48fb9f 1         1         1       15s
replicaset.apps/order-66974757fb          1         1         1       23m
replicaset.apps/payment-66fbb9bb6b        1         1         1       21m
replicaset.apps/reservation-68dc8c44b6    1         1         1       21m

```

**Siege Pod 생성**

```
]root@labs-1603723474:/home/project/reservation# kubectl create -f seige.yaml
apiVersion: v1
kind: Pod
metadata:
  name: siege
spec:
  containers:
  - name: siege
    image: apexacme/siege-nginx
pod/siege created

```
**Siege Pod 생성 확인**

```
]root@labs-1603723474:/home/project/reservation# kubectl get pods
NAME                            READY   STATUS    RESTARTS   AGE
customercenter-6d8c48fb9f-9lsq7 1/1     Running   0          31m
order-66974757fb-7lgmm          1/1     Running   0          55m
payment-66fbb9bb6b-b75h2        1/1     Running   0          53m
reservation-68dc8c44b6-kntqt    1/1     Running   0          53m
siege                           1/1     Running   0          71s

```

## 동기식 호출 / Circuit Breaker

**서킷 브레이킹 프레임워크의 선택: Spring FeignClient + Hystrix 옵션을 사용하여 구현**

시나리오는 주문(order) -> 결제(pay) 처리 연결을 RESTful Request/Response 로 연동하여 구현하고, 결제 요청이 과도할 경우 CB 를 통하여 장애격리 시킨다.

**Hystrix 를 설정: 요청처리 쓰레드에서 처리시간이 300ms가 넘어가기 시작하여 어느 정도 유지되면 CB 회로가 닫혀 요청을 빠르게 실패처리 및 차단하도록 설정**
**임의의 부하처리를 위해 결제서비스내 sleep을 random하게 적용**

```
# order 서비스, application.yml에 Hysrix 타임아웃 설정
feign:
  hystrix:
    enabled: true

hystrix:
  command:
    default:
      execution.isolation.thread.timeoutInMilliseconds: 300
      
```

```
# pay 서비스, Payment.java에 Thread Sleep 처리
public class Payment {

    @PrePersist
    public void onPrePersist(){
        try{
            Thread.sleep((long)(Math.random() * 1000));
       } catch (InterruptedException e){
           e.printStackTrace();
       }
    }
    
```
**부하테스터 Siege 툴을 통한 서킷 브레이커 동작 확인 : 동시사용자 300명, 60초 동안 실시**

```
siege -c300 -t60S -v --content-type "application/json" 'http://order:8080/orders POST {"name": "Jung", "orderType": "prime"}'

HTTP/1.1 500    48.13 secs:     271 bytes ==> POST http://order:8080/orders
HTTP/1.1 201    48.13 secs:     240 bytes ==> POST http://order:8080/orders
HTTP/1.1 201    49.11 secs:     240 bytes ==> POST http://order:8080/orders
HTTP/1.1 201    49.34 secs:     240 bytes ==> POST http://order:8080/orders
HTTP/1.1 201    50.08 secs:     240 bytes ==> POST http://order:8080/orders
HTTP/1.1 500    50.35 secs:     271 bytes ==> POST http://order:8080/orders
HTTP/1.1 201    50.36 secs:     240 bytes ==> POST http://order:8080/orders
HTTP/1.1 201    19.92 secs:     240 bytes ==> POST http://order:8080/orders
HTTP/1.1 500    51.53 secs:     271 bytes ==> POST http://order:8080/orders
HTTP/1.1 201    51.75 secs:     240 bytes ==> POST http://order:8080/orders
HTTP/1.1 201    52.73 secs:     240 bytes ==> POST http://order:8080/orders
HTTP/1.1 201    52.79 secs:     240 bytes ==> POST http://order:8080/orders
HTTP/1.1 201    53.14 secs:     240 bytes ==> POST http://order:8080/orders
HTTP/1.1 201    22.98 secs:     240 bytes ==> POST http://order:8080/orders
HTTP/1.1 201    54.46 secs:     240 bytes ==> POST http://order:8080/orders
HTTP/1.1 201    23.72 secs:     240 bytes ==> POST http://order:8080/orders
HTTP/1.1 201    55.58 secs:     240 bytes ==> POST http://order:8080/orders
HTTP/1.1 500    55.93 secs:     271 bytes ==> POST http://order:8080/orders
HTTP/1.1 201    56.01 secs:     240 bytes ==> POST http://order:8080/orders
HTTP/1.1 201    56.12 secs:     240 bytes ==> POST http://order:8080/orders
HTTP/1.1 201    56.70 secs:     240 bytes ==> POST http://order:8080/orders
HTTP/1.1 201    58.22 secs:     240 bytes ==> POST http://order:8080/orders
HTTP/1.1 201    58.72 secs:     240 bytes ==> POST http://order:8080/orders
HTTP/1.1 201    28.09 secs:     240 bytes ==> POST http://order:8080/orders

Lifting the server siege...
Transactions:                    110 hits
Availability:                  42.31 %
Elapsed time:                  59.40 secs
Data transferred:               0.06 MB
Response time:                 71.77 secs
Transaction rate:               1.85 trans/sec
Throughput:                     0.00 MB/sec
Concurrency:                  132.91
Successful transactions:         110
Failed transactions:             150
Longest transaction:           58.72
Shortest transaction:           1.60
```
**42.31% 성공**

## Config Map

* configmap 생성

```
]root@labs-1603723474:/home/project/pay/kubernetes# kubectl create configmap my-config --from-literal=key1=value1 --from-literal=key2=value2
configmap/my-config created
```
* configmap 정보 가져오기
```
]root@labs-1603723474:/home/project/pay/kubernetes# kubectl get configmaps my-config -o yaml
apiVersion: v1
data:
  key1: value1
  key2: value2
kind: ConfigMap
metadata:
  creationTimestamp: "2021-07-27T02:06:22Z"
  name: my-config
  namespace: gotohotel
  resourceVersion: "121605"
  selfLink: /api/v1/namespaces/gotohotel/configmaps/my-config
  uid: e519bea2-c38e-4ba3-b046-71b5306a9603
```
* 파일로부터 configmap 생성 (configmap.yml 생성)

* 

## Autoscale-out (HPA)

앞서 CB 는 시스템을 안정되게 운영할 수 있게 해줬지만 사용자의 요청을 100% 받아들여주지 못했기 때문에   
이에 대한 보완책으로 자동화된 확장 기능을 적용하고자 한다.

**reservation deployment.yml 파일에 resources 설정 추가**

```
      resources:
        requests:
	  memory: "256mi"
          cpu: 200m
```
**reservation 서비스에 대한 replica 를 동적으로 늘려주도록 HPA 를 설정**

설정은 CPU 사용량이 10프로를 넘어서면 replica 를 10개까지 늘려준다.
```
]root@labs-1603723474:/home/project# kubectl autoscale deployment reservation -n healthcenter --cpu-percent=10 --min=1 --max=10
horizontalpodautoscaler.autoscaling/reservation autoscaled

```

부하를 동시사용자 200명, 60초 동안 걸어준다.
```
root@siege:/# siege –c200 -t60S -v --content-type "application/json" 'http://reservation:8080/reservations POST {"orderId": "12345"}'
```

오토스케일이 어떻게 되고 있는지 모니터링을 걸어둔다.
AVAILABLE 도 증가하며 오토스케일아웃 되는 것을 확인할 수 있다.
```
]root@labs-1603723474:/home/project/reservation/kubernetes# kubectl get deploy reservation -w
NAME          READY   UP-TO-DATE   AVAILABLE   AGE
reservation   10/10   10           10          11m
reservation   9/10    10           9           17m
reservation   8/10    10           8           17m
reservation   7/10    10           7           17m
reservation   6/10    10           6           17m
reservation   5/10    10           5           17m
reservation   4/10    10           4           17m
reservation   3/10    10           3           17m
reservation   2/10    10           2           17m
reservation   1/10    10           1           18m
reservation   0/10    10           0           18m
reservation   1/10    10           1           19m
reservation   2/10    10           2           19m
reservation   3/10    10           3           19m
reservation   4/10    10           4           19m
reservation   5/10    10           5           19m
reservation   6/10    10           6           19m
reservation   7/10    10           7           19m
reservation   8/10    10           8           19m
reservation   9/10    10           9           19m
reservation   10/10   10           10          19m

```


## 무정지 배포(Readiness Probe)

* pay 서비스 deployment.yml 파일에 readinessProbe 설정을 추가하고, 설정 내용을 확인한다.
```
          readinessProbe:
            httpGet:
              path: '/actuator/health'
              port: 8080
            initialDelaySeconds: 10
            timeoutSeconds: 2
            periodSeconds: 5
            failureThreshold: 10 
```
```
]root@labs-1603723474:/home/project/pay/kubernetes# kubectl describe deploy pay
Name:                   pay
Namespace:              gotohotel
CreationTimestamp:      Thu, 08 Jul 2021 09:01:09 +0000
Labels:                 app=pay
Annotations:            deployment.kubernetes.io/revision: 1
Selector:               app=pay
Replicas:               1 desired | 1 updated | 1 total | 1 available | 0 unavailable
StrategyType:           RollingUpdate
MinReadySeconds:        0
RollingUpdateStrategy:  25% max unavailable, 25% max surge
Pod Template:
  Labels:  app=payment
  Containers:
   payment:
    Image:      879772956301.dkr.ecr.au-southeast-1.amazonaws.com/pay:v1
    Port:       8080/TCP
    Host Port:  0/TCP
    Limits:
      cpu:  500m
    Requests:
      cpu:        200m
    Liveness:     http-get http://:8080/actuator/health delay=120s timeout=2s period=5s #success=1 #failure=5
    Readiness:    http-get http://:8080/actuator/health delay=10s timeout=2s period=5s #success=1 #failure=10
    Environment:  <none>
    Mounts:       <none>
  Volumes:        <none>
Conditions:
  Type           Status  Reason
  ----           ------  ------
  Progressing    True    NewReplicaSetAvailable
  Available      True    MinimumReplicasAvailable
OldReplicaSets:  <none>
NewReplicaSet:   pay-8b8df6dd5 (1/1 replicas created)
Events:
  Type    Reason             Age   From                   Message
  ----    ------             ----  ----                   -------
  Normal  ScalingReplicaSet  13m   deployment-controller  Scaled up replica set pay-8b8df6dd5 to 1
```

**부하테스트 실행 및 pay 신규 버전(v2) 배포**

사용자 100명, 60초 동안 부하를 주고 그 사이 새로운 버전(v2) Image 를 반영 후 deployment.yml을 배포한다.

```
# Set image 명령어를 통해 배포 수행
kubectl set image deploy pay pay=879772956301.dkr.ecr.ap-southeast-2.amazonaws.com/pay:v2
```
```
# Siege 로그를 보면서 배포 시 무정지로 배포되는 것을 확인한다.
siege -c100 -t60S -v --content-type "application/json" 'http://pay:8080/payments'

HTTP/1.1 200     0.38 secs:     463 bytes ==> GET  /payments
HTTP/1.1 200     0.28 secs:     463 bytes ==> GET  /payments
HTTP/1.1 200     0.67 secs:     463 bytes ==> GET  /payments
HTTP/1.1 200     0.28 secs:     463 bytes ==> GET  /payments

Lifting the server siege...
Transactions:                   6925 hits
Availability:                 100.00 %
Elapsed time:                  59.32 secs
Data transferred:               3.06 MB
Response time:                  0.70 secs
Transaction rate:             116.74 trans/sec
Throughput:                     0.05 MB/sec
Concurrency:                   81.23
Successful transactions:        6925
Failed transactions:               0
Longest transaction:            3.59
Shortest transaction:           0.00
```

## Self Healing(Liveness Probe)

* deployment.yml 파일 내 livenessProbe 설정에 /tmp/healthy 파일이 존재하는지 확인 후 추가한다.
* periodSeconds 값으로 5초마다/tmp/healthy 파일의 존재 여부를 조회한다.
* 파일이 존재하지 않을 경우, 정상 작동에 문제가 있다고 판단되어 kubelet에 의해 자동으로 컨테이너가 재시작한다.

**pay 서비스의 deployment.yml 파일 수정**

```
livenessProbe:
  exec:
    command:
    - cat 
    - /tmp/healthy
  initialDelaySeconds: 120
  timeoutSeconds: 2
  periodSeconds: 5
  failureThreshold: 5
```

**pay 서비스의 livenessProbe 설정 적용 확인**

```
]root@labs-1603723474:/home/project/pay/kubernetes# kubectl describe pod pay
Name:         pay-65dd7b9cb4-l84p8
Namespace:    default
Priority:     0
Node:         ip-192.168.4.238.au-southeast-2.compute.internal/192.168.4.238
Start Time:   Mon, 26 Jul 2021 18:45:26 +0000
Labels:       app=payment
              pod-template-hash=65dd7b9cb4
Annotations:  kubernetes.io/psp: eks.privileged
Status:       Running
IP:           192.168.4.238
IPs:
  IP:           192.168.4.238
Controlled By:  ReplicaSet/pay-65dd7b9cb4
Containers:
  payment:
    Container ID:   docker://f57bdd65e2b53fc6281928076844b76563a0c2bd9b7462c420c9a2d74b479840
    Image:          879772956301.dkr.ecr.ap-southeast-2.amazonaws.com/pay:v1
    Image ID:       docker-pullable://879772956301.dkr.ecr.ap-southeast-2.amazonaws.com/pay@sha256:e3a45f2ef0b110e76fda9e3226b9a5bb5e096805e8819ffa35e24bd1a02cb223
    Port:           8080/TCP
    Host Port:      0/TCP
    State:          Running
      Started:      Thu, 08 Jul 2021 09:40:34 +0000
    Last State:     Terminated
      Reason:       Error
      Exit Code:    143
      Started:      Thu, 08 Jul 2021 09:40:14 +0000
      Finished:     Thu, 08 Jul 2021 09:40:33 +0000
    Ready:          False
    Restart Count:  4
    Limits:
      cpu:  500m
    Requests:
      cpu:        200m
    Liveness:     exec [cat /tmp/healthy] delay=120s timeout=2s period=5s #success=1 #failure=5               --> 설정 확인
    Readiness:    http-get http://:8080/actuator/health delay=10s timeout=2s period=5s #success=1 #failure=10
    Environment:  <none>
    Mounts:
      /var/run/secrets/kubernetes.io/serviceaccount from default-token-gcvx6 (ro)
Conditions:
  Type              Status
  Initialized       True 
  Ready             False 
  ContainersReady   False 
  PodScheduled      True 
Volumes:
  default-token-gcvx6:
    Type:        Secret (a volume populated by a Secret)
    SecretName:  default-token-gcvx6
    Optional:    false
QoS Class:       Burstable
Node-Selectors:  <none>
Tolerations:     node.kubernetes.io/not-ready:NoExecute for 300s
                 node.kubernetes.io/unreachable:NoExecute for 300s
Events:
  Type     Reason     Age                From                                                      Message
  ----     ------     ----               ----                                                      -------
  Normal   Scheduled  100s               default-scheduler                                         Successfully assigned default/pay-65dd7b9cb4-l84p8 to ip-192-168-65-147.eu-central-1.compute.internal
  Warning  Unhealthy  61s (x4 over 86s)  kubelet, ip-192.168.4.238.au-southeast-2.compute.internal  Readiness probe failed: Get http://192.168.4.238:8080/actuator/health: dial tcp 192.168.4.238:8080: connect: connection refused
  Normal   Killing    59s (x2 over 79s)  kubelet, ip-192.168.4.238.au-southeast-2.compute.internal  Container pay failed liveness probe, will be restarted
  Normal   Pulled     58s (x3 over 98s)  kubelet, ip-192.168.4.238.au-southeast-2.compute.internal  Successfully pulled image "879772956301.dkr.ecr.ap-southeast-2.amazonaws.com/pay:v1"
  Normal   Created    58s (x3 over 98s)  kubelet, ip-192.168.4.238.au-southeast-2.compute.internal  Created container pay
  Normal   Started    58s (x3 over 98s)  kubelet, ip-192.168.4.238.au-southeast-2.compute.internal  Started container pay
  Normal   Pulling    58s (x3 over 98s)  kubelet, ip-192.168.4.238.au-southeast-2.compute.internal  Pulling image "879772956301.dkr.ecr.ap-southeast-2.amazonaws.com/pay:v1"
  Warning  Unhealthy  49s (x7 over 89s)  kubelet, ip-192.168.4.238.au-southeast-2.compute.internal  Liveness probe failed: cat: can't open '/tmp/healthy': No such file or directory
```

* 컨테이너 실행 후 처음에는 /tmp/healthy 파일이 존재하지 않아 livenessProbe에서 실패를 리턴하게 되고,   
  pay Pod가 정상 상태일 때 진입하여 /tmp/healthy 파일 생성해주면 정상 상태가 유지되는 것을 확인한다.
  
```
root@labs-1176757103:/home/project/call-taxi/reservation/kubernetes# kubectl exec payment-585fd6b755-c9jp2 -it sh
/ # touch /tmp/healthy

]root@labs-1603723474:/home/project# kubectl get pods -w
NAME                        READY   STATUS    			RESTARTS   AGE
pay-585fd6b755-c9jp2        0/1     Pending             0          0s
pay-585fd6b755-c9jp2        0/1     ContainerCreating   0          0s
pay-585fd6b755-c9jp2        0/1     Running             0          2s
pay-585fd6b755-c9jp2        0/1     Running             1          74s
pay-585fd6b755-c9jp2        0/1     Running             2          2m25s
pay-585fd6b755-c9jp2        0/1     Running             3          3m34s
pay-585fd6b755-c9jp2        1/1     Running             3          4m53s
```


