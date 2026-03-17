package com.danalfintech.cryptotax.collection.controller;

import com.danalfintech.cryptotax.collection.dto.CollectionStartRequest;
import com.danalfintech.cryptotax.collection.dto.CollectionStatusResponse;
import com.danalfintech.cryptotax.collection.service.CollectionProgressService;
import com.danalfintech.cryptotax.collection.service.CollectionService;
import com.danalfintech.cryptotax.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/collection")
@RequiredArgsConstructor
public class CollectionController {

    private final CollectionService collectionService;
    private final CollectionProgressService progressService;

    @PostMapping("/start")
    public ResponseEntity<CollectionStatusResponse> start(
            @AuthenticationPrincipal CustomUserDetails user,
            @Valid @RequestBody CollectionStartRequest request) {
        Long userId = Long.parseLong(user.getUserId());
        CollectionStatusResponse response = collectionService.startCollection(userId, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/status/{jobId}")
    public ResponseEntity<CollectionStatusResponse> status(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable Long jobId) {
        CollectionStatusResponse response = progressService.getStatus(jobId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/active")
    public ResponseEntity<List<CollectionStatusResponse>> active(
            @AuthenticationPrincipal CustomUserDetails user) {
        Long userId = Long.parseLong(user.getUserId());
        List<CollectionStatusResponse> responses = collectionService.getActiveJobs(userId);
        return ResponseEntity.ok(responses);
    }
}