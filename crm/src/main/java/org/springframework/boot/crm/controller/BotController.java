package org.springframework.boot.crm.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.dto.AgentRequestDto;
import org.springframework.boot.crm.dto.AgentResponseDto;
import org.springframework.boot.crm.dto.RegisterBotRequestDto;
import org.springframework.boot.crm.dto.RegisterBotResponseDto;
import org.springframework.boot.crm.service.BusinessManager;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/bot")
public class BotController {

    private final BusinessManager businessManager;

    public BotController(BusinessManager businessManager) {
        this.businessManager = businessManager;
    }
    @PostMapping("/register")
    public RegisterBotResponseDto addAgent(@RequestBody RegisterBotRequestDto registerBotRequestDto) {
        return new RegisterBotResponseDto("test",1);
    }



}