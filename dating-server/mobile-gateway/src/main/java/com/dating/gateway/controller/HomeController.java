package com.dating.gateway.controller;

import com.dating.gateway.security.RequestContext;
import com.dating.gateway.service.HomeService;
import com.dating.gateway.service.ImService;
import com.dating.gateway.vo.CallTokenVO;
import com.dating.gateway.vo.HomeCardVO;
import com.dating.gateway.vo.ImTokenVO;
import com.dating.gateway.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/** Home Controller. */
@Slf4j
@RestController
@RequestMapping("/api/v1/home")
@RequiredArgsConstructor
@Tag(name = "Home", description = "Home page endpoints")
public class HomeController {

    private final HomeService homeService;
    private final ImService imService;

    @GetMapping("/card")
    @Operation(summary = "Get home card")
    public Result<HomeCardVO> getHomeCard(@RequestParam Long targetId) {
        var ctx = RequestContext.current();
        return Result.ok(homeService.getHomeCard(ctx.getUserId(), targetId));
    }
}
