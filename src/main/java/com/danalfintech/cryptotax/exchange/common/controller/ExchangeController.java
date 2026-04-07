package com.danalfintech.cryptotax.exchange.common.controller;


import com.danalfintech.cryptotax.exchange.common.dto.*;
import com.danalfintech.cryptotax.exchange.common.service.ExchangeService;
import com.danalfintech.cryptotax.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/exchanges")
@RequiredArgsConstructor
public class ExchangeController {

    private final ExchangeService exchangeService;

    @PostMapping("/keys")
    public ResponseEntity<ExchangeKeyResponse> registerKey(
            @AuthenticationPrincipal CustomUserDetails user,
            @Valid @RequestBody ExchangeKeyRequest request) {
        Long userId = Long.parseLong(user.getUserId());
        ExchangeKeyResponse response = exchangeService.register(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/keys")
    public ResponseEntity<ExchangeKeyListResponse> getKeys(
            @AuthenticationPrincipal CustomUserDetails user) {
        Long userId = Long.parseLong(user.getUserId());
        List<ExchangeKeyResponse> keyList = exchangeService.getKeys(userId);
        return ResponseEntity.ok(ExchangeKeyListResponse.from(keyList));
    }

    @PutMapping("/keys/{id}")
    public ResponseEntity<ExchangeKeyResponse> updateKey(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable Long id,
            @Valid @RequestBody ExchangeKeyUpdateRequest request) {
        Long userId = Long.parseLong(user.getUserId());
        ExchangeKeyResponse response = exchangeService.update(userId, id, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/keys/{id}")
    public ResponseEntity<Void> deleteKey(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable Long id) {
        Long userId = Long.parseLong(user.getUserId());
        exchangeService.delete(userId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/keys/{id}/verify")
    public ResponseEntity<ExchangeVerifyResponse> verifyKey(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable Long id) {
        Long userId = Long.parseLong(user.getUserId());
        ExchangeVerifyResponse response = exchangeService.verify(userId, id);
        return ResponseEntity.ok(response);
    }
}
