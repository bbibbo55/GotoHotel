package gotohotel;

import javax.persistence.*;
import java.util.List;

@Entity
@Table(name="Mypage_table")
public class Mypage {

        @Id
        @GeneratedValue(strategy=GenerationType.AUTO)
        private Long id;
        private Long orderId;
        private String name;
        private String roomType;
        private Integer guestCnt;
        private String status;


        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }
        public Long getOrderId() {
            return orderId;
        }

        public void setOrderId(Long orderId) {
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

}
