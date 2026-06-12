package com.premisave.messenger.client;

import com.premisave.messenger.dto.response.UserSummaryResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "auth-service", url = "${auth.service.url:http://localhost:8080}")
public interface UserFeignClient {

    @GetMapping("/profile/user/{userId}")
    UserSummaryResponse getUserById(
            @PathVariable String userId,
            @RequestHeader("Authorization") String authorizationHeader
    );
}