package com.dating.gateway.controller;

import com.dating.gateway.security.RequestContext;
import com.dating.gateway.service.ImService;
import com.dating.gateway.vo.CallTokenVO;
import com.dating.gateway.vo.ImTokenVO;
import com.dating.gateway.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/** IM Controller. */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "IM", description = "IM and call token endpoints")
public class ImController {

    private final ImService imService;

    @GetMapping("/im/token")
    @Operation(summary = "Get IM token")
    public Result<ImTokenVO> getImToken() {
        var ctx = RequestContext.current();
        return Result.ok(imService.getImToken(ctx.getUserId()));
    }

    @GetMapping("/call/token")
    @Operation(summary = "Get call token")
    public Result<CallTokenVO> getCallToken(@RequestParam String peerId) {
        var ctx = RequestContext.current();
        return Result.ok(imService.getCallToken(ctx.getUserId(), peerId));
    }
}
