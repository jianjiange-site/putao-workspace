package com.dating.gateway.controller;

import com.dating.gateway.dto.SuperHiReq;
import com.dating.gateway.dto.SwipeReq;
import com.dating.gateway.security.RequestContext;
import com.dating.gateway.service.MatchService;
import com.dating.gateway.vo.MatchCardVO;
import com.dating.gateway.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Match Controller. */
@RestController
@RequestMapping("/api/v1/match")
@Tag(name = "Match", description = "Match and swipe endpoints")
public class MatchController {

    private static final Logger log = LoggerFactory.getLogger(MatchController.class);

    private final MatchService matchService;

    public MatchController(MatchService matchService) {
        this.matchService = matchService;
    }

    @GetMapping("/feed")
    @Operation(summary = "Get match feed")
    public Result<List<MatchCardVO>> getFeed(@RequestParam(defaultValue = "5") int count) {
        var ctx = RequestContext.current();
        return Result.ok(matchService.getFeed(ctx.getUserId(), count));
    }

    @PostMapping("/swipe")
    @Operation(summary = "Swipe")
    public Result<Boolean> swipe(@Valid @RequestBody SwipeReq req) {
        var ctx = RequestContext.current();
        return Result.ok(matchService.swipe(ctx.getUserId(), req));
    }

    @PostMapping("/super-hi")
    @Operation(summary = "Super Hi")
    public Result<Boolean> superHi(@Valid @RequestBody SuperHiReq req) {
        var ctx = RequestContext.current();
        return Result.ok(matchService.superHi(ctx.getUserId(), req));
    }
}
