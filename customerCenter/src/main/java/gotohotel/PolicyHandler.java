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

    @StreamListener(KafkaProcessor.INPUT)
    public void wheneverReserveTaked_NotifyKakao(@Payload ReserveTaked reserveTaked){

        if(!reserveTaked.validate()) return;

        System.out.println("\n\n##### listener NotifyKakao : " + reserveTaked.toJson() + "\n\n");



        // Sample Logic //

    }
    @StreamListener(KafkaProcessor.INPUT)
    public void wheneverPaymentCanceled_NotifyKakao(@Payload PaymentCanceled paymentCanceled){

        if(!paymentCanceled.validate()) return;

        System.out.println("\n\n##### listener NotifyKakao : " + paymentCanceled.toJson() + "\n\n");



        // Sample Logic //

    }


    @StreamListener(KafkaProcessor.INPUT)
    public void whatever(@Payload String eventString){}


}
