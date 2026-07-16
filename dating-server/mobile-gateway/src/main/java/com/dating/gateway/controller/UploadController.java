package com.dating.gateway.controller;

import com.dating.gateway.dto.ConfirmUploadReq;
import com.dating.gateway.dto.PresignReq;
import com.dating.gateway.security.RequestContext;
import com.dating.gateway.service.UploadService;
import com.dating.gateway.vo.AvatarVO;
import com.dating.gateway.vo.PresignAvatarUploadVO;
import com.dating.gateway.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/** Upload Controller. */
@Slf4j
@RestController
@RequestMapping("/api/v1/upload")
@RequiredArgsConstructor
@Tag(name = "Upload", description = "File upload endpoints")
public class UploadController {

    private final UploadService uploadService;

    @PostMapping("/presign")
    @Operation(summary = "Presign upload")
    public Result<PresignAvatarUploadVO> presign(@Valid @RequestBody PresignReq req) {
        var ctx = RequestContext.current();
        return Result.ok(uploadService.presign(ctx.getUserId(), req));
    }

    @PostMapping("/confirm")
    @Operation(summary = "Confirm upload")
    public Result<AvatarVO> confirm(@Valid @RequestBody ConfirmUploadReq req) {
        var ctx = RequestContext.current();
        return Result.ok(uploadService.confirm(ctx.getUserId(), req));
    }
}
