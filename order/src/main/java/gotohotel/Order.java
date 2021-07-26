package gotohotel;

import javax.persistence.*;
import org.springframework.beans.BeanUtils;
import java.util.List;
import java.util.Date;

@Entity
@Table(name="Order_table")
public class Order {

    @Id
    @GeneratedValue(strategy=GenerationType.AUTO)
    private Long id;
    private String roomType;
    private String name;
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

        gotohotel.external.Payment payment = new gotohotel.external.Payment();
        // mappings goes here
        Application.applicationContext.getBean(gotohotel.external.PaymentService.class)
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

    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }
    public String getRoomType() {
        return roomType;
    }

    public void setRoomType(String roomType) {
        this.roomType = roomType;
    }
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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
