package org.kert0n.medappserver.integration.userstory

import java.math.BigDecimal
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.kert0n.medappserver.api.DrugCreateRequest
import org.kert0n.medappserver.testutil.ApiTestClient
import org.kert0n.medappserver.testutil.AuthenticatedApiTestClient
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/** Общая HTTP-граница историй; база используется только для недоступных через API фикстур. */
abstract class HttpUserStoryTest {

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var json: Json
    @Autowired private lateinit var database: DatabaseTestHelper

    protected lateinit var api: ApiTestClient

    @BeforeEach
    fun setUpHttpClient() {
        val mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
            .build()
        api = ApiTestClient(mockMvc, json)
    }

    protected fun actor(tag: String): StoryActor {
        val userId = database.freshUser("user-story-$tag").id
        return StoryActor(userId, api.asUser(userId))
    }

    protected fun drugRequest(
        name: String,
        quantity: String,
        id: Uuid = Uuid.random()
    ): DrugCreateRequest = DrugCreateRequest(
        id = id,
        name = name,
        quantity = BigDecimal(quantity),
        quantityUnitId = database.unit().id
    )
}

data class StoryActor(
    val id: Uuid,
    val api: AuthenticatedApiTestClient
)
