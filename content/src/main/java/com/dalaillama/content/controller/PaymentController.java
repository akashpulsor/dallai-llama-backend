package com.dalaillama.content.controller;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/payment")
public class PaymentController {

    /*
    {
        url: '/payment',
                method: 'POST',
            body: {"userId":userId, "money":money,"currencyId":currencyId},
    }

 url: /addCredit
 method: 'POST'
     */
}
