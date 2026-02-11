package com.nxthn.AIChatbot.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nxthn.AIChatbot.model.ResponseStatus;


@RestController
public class AIbotController {

    @GetMapping("/healthz")
    public ResponseStatus healthCheck() {
        return new ResponseStatus("OK");
    }
}