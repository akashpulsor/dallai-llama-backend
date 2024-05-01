package com.dalaillama.content.controller;


import com.dalaillama.content.dto.ChatResponseGenerated;
import com.dalaillama.content.dto.UserDto;
import com.dalaillama.content.dto.UserHistoryDto;
import com.dalaillama.content.entity.User;
import com.dalaillama.content.service.UserManager;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserManager userManager;

    public UserController(UserManager userManager) {
        this.userManager = userManager;
    }

    @GetMapping("/getHistory")
    public List<UserHistoryDto> getHistory(@RequestParam(value = "userId", required = true) int userId ) {
        return this.userManager.getUserHistory(userId);
    }

    @GetMapping("/getUser")
    public UserDto getUser(@RequestParam(value = "userId", required = true) int userId) {
        return this.userManager.getUser(userId);
    }

    @PostMapping("/update")
    public UserDto updateUser(@RequestBody UserDto userDto) {
        return this.userManager.updateUser(userDto);
    }
}
