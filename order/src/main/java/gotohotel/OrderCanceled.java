package gotohotel;

public class OrderCanceled extends AbstractEvent {

    private Long id;
    private String name;
    private String roomType;
    private Integer guestCnt;
    private String status;
    private Long cardNo;

    public OrderCanceled(){
        super();
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
