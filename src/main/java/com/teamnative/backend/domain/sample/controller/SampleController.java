package com.teamnative.backend.domain.sample.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Sample", description = "샘플 API")
public class SampleController {

    @GetMapping("/public/ping")
    @Operation(summary = "공개 핑", description = "인증 없이 호출 가능한 핑 API입니다.")
    public Map<String, String> publicPing() {
        return Map.of("message", "public pong");
    }

    @GetMapping("/private/ping")
    @Operation(summary = "비공개 핑", description = "인증이 필요한 핑 API입니다.")
    public Map<String, String> privatePing() {
        return Map.of("message", "private pong");
    }
}
