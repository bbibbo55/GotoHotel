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

        if(!paymentApproved.validate()) return;

        System.out.println("\n\n##### listener TakeReserve : " + paymentApproved.toJson() + "\n\n");



        // Sample Logic //
        // Reservation reservation = new Reservation();
        // reservationRepository.save(reservation);

    }
    @StreamListener(KafkaProcessor.INPUT)
    public void wheneverOrderCanceled_CancelReserve(@Payload OrderCanceled orderCanceled){

        if(!orderCanceled.validate()) return;

        System.out.println("\n\n##### listener CancelReserve : " + orderCanceled.toJson() + "\n\n");



        // Sample Logic //
        // Reservation reservation = new Reservation();
        // reservationRepository.save(reservation);

    }


    @StreamListener(KafkaProcessor.INPUT)
    public void whatever(@Payload String eventString){}


}
