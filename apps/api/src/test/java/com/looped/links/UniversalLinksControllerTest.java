package com.looped.links;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UniversalLinksControllerTest {

    @Test
    void aasa_is_served_with_json_cache_headers_and_expected_paths() throws Exception {
        UniversalLinksProperties props = new UniversalLinksProperties();
        props.setAppleTeamId("ABCDE12345");
        props.setIosBundleId("app.mylooped.ios");
        props.setCacheMaxAgeSeconds(300);
        props.setAasaVersion("2026-02-14");

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new UniversalLinksController(props)).build();

        mockMvc.perform(get("/.well-known/apple-app-site-association"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", containsString("max-age=300")))
                .andExpect(header().string("Cache-Control", containsString("public")))
                .andExpect(header().string("ETag", "\"2026-02-14\""))
                .andExpect(jsonPath("$.applinks.apps").isArray())
                .andExpect(jsonPath("$.applinks.details[0].appID").value("ABCDE12345.app.mylooped.ios"))
                .andExpect(jsonPath("$.applinks.details[0].paths[0]").value("/p/*"))
                .andExpect(jsonPath("$.applinks.details[0].paths[1]").value("/u/*"));
    }

    @Test
    void assetlinks_returns_empty_array_when_android_values_are_unset() throws Exception {
        UniversalLinksProperties props = new UniversalLinksProperties();

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new UniversalLinksController(props)).build();

        mockMvc.perform(get("/.well-known/assetlinks.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("[]"));
    }

    @Test
    void assetlinks_returns_statement_when_android_values_are_set() throws Exception {
        UniversalLinksProperties props = new UniversalLinksProperties();
        props.setAndroidPackageName("app.mylooped.android");
        props.setAndroidSha256CertFingerprint("AA:BB:CC:DD");

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new UniversalLinksController(props)).build();

        mockMvc.perform(get("/.well-known/assetlinks.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].relation[0]").value("delegate_permission/common.handle_all_urls"))
                .andExpect(jsonPath("$[0].target.namespace").value("android_app"))
                .andExpect(jsonPath("$[0].target.package_name").value("app.mylooped.android"))
                .andExpect(jsonPath("$[0].target.sha256_cert_fingerprints[0]").value("AA:BB:CC:DD"));
    }
}
