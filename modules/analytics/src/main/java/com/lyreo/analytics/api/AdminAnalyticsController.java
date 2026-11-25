package com.lyreo.analytics.api;

import com.lyreo.analytics.application.AnalyticsService;
import com.lyreo.analytics.domain.AdminAnalyticsSummary;
import com.lyreo.analytics.domain.AnalyticsOverview;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Administrative platform and per-learner analytics projections. */
@RestController
@RequestMapping("/api/v1/admin/analytics")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAnalyticsController {
    private final AnalyticsService analytics;

    public AdminAnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/summary")
    public AdminAnalyticsSummary summary() {
        return analytics.platformSummary();
    }

    @GetMapping("/learners/{learnerId}")
    public AnalyticsOverview learnerOverview(@PathVariable UUID learnerId) {
        return analytics.overview(learnerId);
    }
}
