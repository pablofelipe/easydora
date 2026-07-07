package com.easydora.authservice.controller;

import com.easydora.authservice.dto.UserNotificationProfileResponse;
import com.easydora.authservice.entity.User;
import com.easydora.authservice.repository.UserRepository;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserQueryController {

    private final UserRepository userRepository;

    public UserQueryController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/users/{id}/notification-profile")
    public ResponseEntity<UserNotificationProfileResponse> getNotificationProfile(@PathVariable Long id) {
        return userRepository.findById(id)
                .map(this::toNotificationProfile)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private UserNotificationProfileResponse toNotificationProfile(User user) {
        return new UserNotificationProfileResponse(user.getId(), user.getEmail(), user.getFirstName(), user.getLastName());
    }
}
