package com.nexi.moderation

import com.nexi.auth.ApiException
import com.nexi.posts.FakeObjectStorage
import io.ktor.http.HttpStatusCode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModerationServiceTest {
    private val now = Instant.parse("2026-08-23T00:00:00Z")
    private val repository = InMemoryModerationRepository(now)
    private val service = ModerationService(
        repository = repository,
        users = { username -> repository.idByUsername(username) },
        storage = FakeObjectStorage(),
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    private val meId = repository.addUser("Gizem Derici", "gizem")
    private val otherId = repository.addUser("Mert Arslan", "mert")

    // ------------------------------------------------------------- engelleme

    @Test
    fun `blocking works in both directions`() {
        service.setBlock(meId, "mert", true)

        assertTrue(repository.isBlockedEitherWay(meId, otherId))
        // Kayit tek yonlu ama etki cift yonlu: engellenen de engelleyeni gormemeli.
        assertTrue(repository.isBlockedEitherWay(otherId, meId))
    }

    @Test
    fun `blocking removes the follow relationship in both directions`() {
        repository.follows += meId to otherId
        repository.follows += otherId to meId

        service.setBlock(meId, "mert", true)

        assertTrue(repository.follows.isEmpty(), "engelleme karsilikli takibi kaldirmali")
    }

    @Test
    fun `unblocking does not restore the old follow`() {
        repository.follows += meId to otherId
        service.setBlock(meId, "mert", true)

        service.setBlock(meId, "mert", false)

        assertFalse(repository.isBlockedEitherWay(meId, otherId))
        assertTrue(repository.follows.isEmpty(), "takip kendiliginden geri gelmemeli")
    }

    @Test
    fun `blocking is idempotent`() {
        assertTrue(service.setBlock(meId, "mert", true).blocked)
        assertTrue(service.setBlock(meId, "mert", true).blocked)

        assertEquals(1, service.blockedUsers(meId, null, null).totalCount)
    }

    @Test
    fun `you cannot block yourself`() {
        assertEquals("CANNOT_BLOCK_SELF", assertFailsWith<ApiException> {
            service.setBlock(meId, "gizem", true)
        }.code)
    }

    @Test
    fun `blocking an unknown user is a not found`() {
        assertEquals(HttpStatusCode.NotFound, assertFailsWith<ApiException> {
            service.setBlock(meId, "yokboyle", true)
        }.status)
    }

    @Test
    fun `the blocked list is paged and only shows your own blocks`() {
        val blockedNames = listOf("mert", "kullanici1", "kullanici2", "kullanici3")
        blockedNames.drop(1).forEach { repository.addUser("Kullanici $it", it) }
        blockedNames.forEach { service.setBlock(meId, it, true) }
        // Baskasinin engeli benim listeme karismamali.
        service.setBlock(otherId, "gizem", true)

        val first = service.blockedUsers(meId, null, 2)
        assertEquals(2, first.items.size)
        assertEquals(4, first.totalCount)

        val second = service.blockedUsers(meId, assertNotNull(first.nextCursor), 10)
        val all = (first.items + second.items).map { it.username }

        assertEquals(blockedNames.toSet(), all.toSet())
        assertEquals(all.distinct().size, all.size)
    }

    // --------------------------------------------------------------- sikayet

    @Test
    fun `a post report is stored as open`() {
        val postId = repository.addTarget(ReportTargetType.POST)

        val report = service.report(meId, CreateReportRequest("POST", postId.toString(), "SPAM", "Tekrarlayan icerik"))

        assertEquals("OPEN", report.status)
        assertEquals("POST", report.targetType)
        assertEquals("Tekrarlayan icerik", report.details)
        assertFalse(report.alreadyReported)
    }

    @Test
    fun `reporting the same target twice returns the first report`() {
        val postId = repository.addTarget(ReportTargetType.POST)
        val first = service.report(meId, CreateReportRequest("POST", postId.toString(), "SPAM"))

        val second = service.report(meId, CreateReportRequest("POST", postId.toString(), "HARASSMENT"))

        assertTrue(second.alreadyReported)
        assertEquals(first.id, second.id)
        // Neden degismedi: ilk kayit korunuyor.
        assertEquals("SPAM", second.reason)
        assertEquals(1, service.reports(meId, null, null).totalCount)
    }

    @Test
    fun `reason and target type are case insensitive but must be known`() {
        val postId = repository.addTarget(ReportTargetType.POST)

        assertEquals("SPAM", service.report(meId, CreateReportRequest("post", postId.toString(), "spam")).reason)

        assertEquals("INVALID_REPORT_TARGET", assertFailsWith<ApiException> {
            service.report(meId, CreateReportRequest("BILINMEYEN", postId.toString(), "SPAM"))
        }.code)
        assertEquals("INVALID_REPORT_REASON", assertFailsWith<ApiException> {
            service.report(meId, CreateReportRequest("POST", postId.toString(), "CANIM_ISTEDI"))
        }.code)
    }

    @Test
    fun `stories and messages are refused until those features exist`() {
        val target = UUID.randomUUID().toString()

        listOf("STORY", "MESSAGE").forEach { type ->
            assertEquals("UNSUPPORTED_REPORT_TARGET", assertFailsWith<ApiException> {
                service.report(meId, CreateReportRequest(type, target, "SPAM"))
            }.code, "$type henuz desteklenmiyor demeli")
        }
    }

    @Test
    fun `a missing target is refused`() {
        assertEquals("REPORT_TARGET_NOT_FOUND", assertFailsWith<ApiException> {
            service.report(meId, CreateReportRequest("POST", UUID.randomUUID().toString(), "SPAM"))
        }.code)
    }

    @Test
    fun `you cannot report yourself`() {
        assertEquals("CANNOT_REPORT_SELF", assertFailsWith<ApiException> {
            service.report(meId, CreateReportRequest("USER", meId.toString(), "SPAM"))
        }.code)
    }

    @Test
    fun `overlong details are refused`() {
        val postId = repository.addTarget(ReportTargetType.POST)

        assertEquals("DETAILS_TOO_LONG", assertFailsWith<ApiException> {
            service.report(
                meId,
                CreateReportRequest("POST", postId.toString(), "SPAM", "a".repeat(ModerationService.MAX_DETAILS_LENGTH + 1)),
            )
        }.code)
    }

    @Test
    fun `your report list is yours alone and newest first`() {
        val first = repository.addTarget(ReportTargetType.POST)
        val second = repository.addTarget(ReportTargetType.COMMENT)
        service.report(meId, CreateReportRequest("POST", first.toString(), "SPAM"))
        service.report(meId, CreateReportRequest("COMMENT", second.toString(), "HARASSMENT"))
        service.report(otherId, CreateReportRequest("POST", first.toString(), "SPAM"))

        val mine = service.reports(meId, null, null)

        assertEquals(2, mine.totalCount)
        assertEquals(listOf("COMMENT", "POST"), mine.items.map { it.targetType })
    }

    @Test
    fun `a broken cursor is refused`() {
        assertEquals("INVALID_CURSOR", assertFailsWith<ApiException> {
            service.blockedUsers(meId, "bozuk-imlec", null)
        }.code)
    }

    @Test
    fun `an empty details field is stored as absent`() {
        val postId = repository.addTarget(ReportTargetType.POST)

        assertNull(service.report(meId, CreateReportRequest("POST", postId.toString(), "SPAM", "   ")).details)
    }
}
