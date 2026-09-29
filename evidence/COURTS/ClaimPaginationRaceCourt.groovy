package com.github.oinsio.gnomish.adapter.tracker.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.delete
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.post
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.adapter.github.GithubHttpClient
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.tomakehurst.wiremock.WireMockServer
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse
import java.time.Instant
import spock.lang.Specification

/**
 * Executable Court / Reproducer for GithubClaimLease >100-comment pagination race.
 *
 * Vulnerability:
 * GithubClaimLease.listComments calls:
 *   /repos/{owner}/{repo}/issues/{n}/comments?per_page=100
 * without following pagination Link headers or requesting subsequent pages.
 *
 * Scenario:
 * - 100 comments occupy the first page (comments 1..100).
 * - Beyond page 1 (comment 101): a boundary marker (e.g. abort/finish/park) ends prior tenures.
 * - Beyond page 1 (comment 102): competing claim from instance A.
 * - Beyond page 1 (comment 103): competing claim from instance B.
 *
 * Because GitHub returns only the first page (comments 1..100), neither instance sees:
 * 1. The boundary marker (comment 101)
 * 2. Competing claims (102 and 103)
 * 3. Even their own newly posted comment
 *
 * Result:
 * window.winnerAgainst(ownCommentId) returns Optional.empty() for both instances.
 * Both instances fall back to winnerId = ownCommentId and return ClaimResult.Acquired!
 * Both instance A and instance B believe they hold the exclusive lease on the task.
 */
class ClaimPaginationRaceCourt extends Specification {

    WireMockServer wireMock

    def setup() {
        wireMock = new WireMockServer(0)
        wireMock.start()
    }

    def cleanup() {
        wireMock.stop()
    }

    private static RetryConfig fastRetryConfig() {
        RetryConfig.custom()
                .maxAttempts(2)
                .intervalFunction(IntervalFunction.of(10))
                .retryOnException({ true })
                .retryOnResult({ HttpResponse<?> r -> r.statusCode() >= 500 })
                .build()
    }

    private GithubClaimLease newLease(String readyLabel = 'gnomish:ready',
            String workingLabel = 'gnomish:working') {
        def httpClient = new GithubHttpClient(wireMock.baseUrl(), 'tok', fastRetryConfig())
        def labelOps = new GithubLabelOps(httpClient)
        new GithubClaimLease(httpClient, labelOps, readyLabel, workingLabel)
    }

    private TaskRef refFor(int issueNumber) {
        new TaskRef(GithubTaskId.build(wireMock.baseUrl(), 'acme', 'widgets', issueNumber).canonicalId())
    }

    private static void stubLabelCalls(WireMockServer wireMock, int issueNumber) {
        wireMock.stubFor(post(urlEqualTo("/repos/acme/widgets/issues/${issueNumber}/labels"))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        wireMock.stubFor(delete(urlEqualTo("/repos/acme/widgets/issues/${issueNumber}/labels/gnomish%3Aready"))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
    }

    def "CONFIRMED: two competing claimers beyond comment 100 both acquire the lease"() {
        given: "an issue where 100 comments occupy the first page"
        int issueNumber = 99
        stubLabelCalls(wireMock, issueNumber)

        // 100 regular non-marker discussion comments filling page 1
        def page1Comments = (1..100).collect { i ->
            """{"id":${i},"body":"Plain issue comment #${i}","created_at":"2026-07-20T10:00:00Z"}"""
        }.join(',')
        def page1Json = "[${page1Comments}]"

        // Instance A posts claim comment 102
        wireMock.stubFor(post(urlEqualTo("/repos/acme/widgets/issues/${issueNumber}/comments"))
                .inScenario("claim-pagination-race")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(201).withBody('{"id":102,"body":"whatever"}'))
                .willSetStateTo("A posted"))

        // Instance B posts claim comment 103
        wireMock.stubFor(post(urlEqualTo("/repos/acme/widgets/issues/${issueNumber}/comments"))
                .inScenario("claim-pagination-race")
                .whenScenarioStateIs("A posted")
                .willReturn(aResponse().withStatus(201).withBody('{"id":103,"body":"whatever"}')))

        // listComments only requests page 1 (?per_page=100) and receives comments 1..100
        wireMock.stubFor(get(urlEqualTo("/repos/acme/widgets/issues/${issueNumber}/comments?per_page=100"))
                .willReturn(aResponse().withStatus(200).withBody(page1Json)))

        def leaseA = newLease()
        def leaseB = newLease()

        when: "instance A and instance B both attempt to claim the task"
        def resultA = leaseA.claim(refFor(issueNumber), "gnomish-factory-a")
        def resultB = leaseB.claim(refFor(issueNumber), "gnomish-factory-b")

        then: "CRITICAL FLAW: BOTH claimers return Acquired instead of one returning Held"
        // Instance A acquires lease with epoch 102
        resultA instanceof ClaimResult.Acquired
        ((ClaimResult.Acquired) resultA).epoch() == new ClaimEpoch(102)

        // Instance B ALSO acquires lease with epoch 103
        resultB instanceof ClaimResult.Acquired
        ((ClaimResult.Acquired) resultB).epoch() == new ClaimEpoch(103)

        and: "neither claimer deletes its comment because neither saw the other"
        wireMock.findAll(deleteRequestedFor(urlEqualTo("/repos/acme/widgets/issues/comments/102"))).isEmpty()
        wireMock.findAll(deleteRequestedFor(urlEqualTo("/repos/acme/widgets/issues/comments/103"))).isEmpty()
    }
}
