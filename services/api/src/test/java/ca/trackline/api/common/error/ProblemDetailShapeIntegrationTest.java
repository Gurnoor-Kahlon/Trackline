package ca.trackline.api.common.error;

import ca.trackline.api.testsupport.PostgisIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The shape of an error as the running application writes it.
 *
 * <p>The controller tests run in a slice with its own JSON converter and cannot see this: in the full application an
 * error's extra fields were nested under "properties" until they were made top-level, so the contract those tests checked
 * was not the one served.
 */
@AutoConfigureMockMvc
class ProblemDetailShapeIntegrationTest extends PostgisIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void anErrorsExtraFieldsAreTopLevelMembersNotNestedUnderProperties() throws Exception {
        mvc.perform(get("/api/v1/routes/definitely-not-a-route"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.resourceType").value("Route"))
                .andExpect(jsonPath("$.resourceId").value("definitely-not-a-route"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.properties").doesNotExist());
    }

    @Test
    void aValidationErrorListsItsViolationsAtTheTopLevelToo() throws Exception {
        mvc.perform(get("/api/v1/routes").param("size", "100000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations").isNotEmpty())
                .andExpect(jsonPath("$.properties").doesNotExist());
    }
}
