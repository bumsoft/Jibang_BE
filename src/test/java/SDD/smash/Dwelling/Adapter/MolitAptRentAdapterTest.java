package SDD.smash.Dwelling.Adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.YearMonth;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MolitAptRentAdapterTest {
    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final MolitAptRentAdapter adapter = new MolitAptRentAdapter(
            restTemplate, new ObjectMapper(), new XmlMapper());

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(adapter, "baseUrl", "https://molit.invalid");
        ReflectionTestUtils.setField(adapter, "apiPath", "rent");
        ReflectionTestUtils.setField(adapter, "serviceKey", "test-only");
    }

    @Test
    void readsJsonRecords() {
        respond("""
                {"response":{"header":{"resultCode":"000"},"body":{
                "totalCount":1,"items":{"item":[{"aptNm":"아파트","deposit":"1,000","monthlyRent":"50"}]}}}}
                """, MediaType.APPLICATION_JSON);

        var records = adapter.fetchMonth("26110", YearMonth.of(2026, 10), 1, 1000);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).getDeposit()).isEqualTo(1000);
        assertThat(records.get(0).getMonthlyRent()).isEqualTo(50);
        server.verify();
    }

    @Test
    void readsXmlRecordsWhoseRootIsNotWrappedByJackson() {
        respond("""
                <response><header><resultCode>000</resultCode></header><body>
                <totalCount>1</totalCount><items><item><aptNm>아파트</aptNm>
                <deposit>1000</deposit><monthlyRent>50</monthlyRent></item></items>
                </body></response>
                """, MediaType.APPLICATION_XML);

        var records = adapter.fetchMonth("26110", YearMonth.of(2026, 10), 1, 1000);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).getMonthlyRent()).isEqualTo(50);
        server.verify();
    }

    @Test
    void acceptsExplicitlySuccessfulZeroCountResponse() {
        respond("""
                <response><header><resultCode>00</resultCode></header><body>
                <totalCount>0</totalCount><items/></body></response>
                """, MediaType.APPLICATION_XML);

        assertThat(adapter.fetchMonth("26110", YearMonth.of(2026, 10), 1, 1000)).isEmpty();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "not JSON or XML",
            "<html><body>Service unavailable</body></html>",
            "{\"response\":{\"header\":{\"resultCode\":\"30\"}}}",
            "{\"response\":{\"header\":{\"resultCode\":\"000\"},\"body\":{}}}",
            "{\"response\":{\"header\":{\"resultCode\":\"000\"},\"body\":{\"totalCount\":1,\"items\":{}}}}"
    })
    void rejectsErrorsAndMalformedResponsesInsteadOfReturningNoRecords(String body) {
        respond(body, MediaType.TEXT_PLAIN);

        assertThatThrownBy(() -> adapter.fetchMonth("26110", YearMonth.of(2026, 10), 1, 1000))
                .isInstanceOf(RestClientException.class);
        server.verify();
    }

    private void respond(String body, MediaType mediaType) {
        server.expect(queryParam("LAWD_CD", "26110"))
                .andExpect(queryParam("DEAL_YMD", "202610"))
                .andRespond(withSuccess(body, mediaType));
    }
}
