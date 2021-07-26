package gotohotel;

import gotohotel.config.kafka.KafkaProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Service
public class MypageViewHandler {


    @Autowired
    private MypageRepository mypageRepository;

    @StreamListener(KafkaProcessor.INPUT)
    public void whenOrdered_then_CREATE_1 (@Payload Ordered ordered) {
        try {

            if (!ordered.validate()) return;

            // view 객체 생성
            Mypage mypage = new Mypage();
            // view 객체에 이벤트의 Value 를 set 함
            mypage.setOrderId(ordered.getId());
            mypage.setName(ordered.getName());
            mypage.setRoomType(ordered.getRoomType());
            mypage.setGuestCnt(ordered.getGuestCnt());
            mypage.setStatus(ordered.getStatus());
            // view 레파지 토리에 save
            mypageRepository.save(mypage);

        }catch (Exception e){
            e.printStackTrace();
        }
    }


    @StreamListener(KafkaProcessor.INPUT)
    public void whenReserveTaked_then_UPDATE_1(@Payload ReserveTaked reserveTaked) {
        try {
            if (!reserveTaked.validate()) return;
                // view 객체 조회

                    List<Mypage> mypageList = mypageRepository.findByOrderId(reserveTaked.getOrderId());
                    for(Mypage mypage : mypageList){
                    // view 객체에 이벤트의 eventDirectValue 를 set 함
                    mypage.setOrderId(reserveTaked.getOrderId());
                    mypage.setStatus(reserveTaked.getStatus());
                // view 레파지 토리에 save
                mypageRepository.save(mypage);
                }

        }catch (Exception e){
            e.printStackTrace();
        }
    }

    @StreamListener(KafkaProcessor.INPUT)
    public void whenReserveCanceled_then_DELETE_1(@Payload ReserveCanceled reserveCanceled) {
        try {
            if (!reserveCanceled.validate()) return;
            // view 레파지 토리에 삭제 쿼리
            mypageRepository.deleteByStatus(reserveCanceled.getStatus());
        }catch (Exception e){
            e.printStackTrace();
        }
    }
}

