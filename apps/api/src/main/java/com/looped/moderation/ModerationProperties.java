package com.looped.moderation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "moderation")
public class ModerationProperties {
    private boolean enabled = true;
    private int reportQuarantineThreshold = 10;
    private String blocklistResource = "classpath:moderation/blocklist.txt";
    private String blocklistTerms;
    private String openaiApiKey;
    private boolean openaiEnabled = false;
    private String openaiModel = "omni-moderation-latest";
    private String openaiBaseUrl = "https://api.openai.com/v1";
    private String openaiCategoryBlocklist = "hate,hate/threatening,sexual,sexual/minors,self-harm,self-harm/intent,self-harm/instructions,violence,violence/graphic";
    private int openaiTimeoutMillis = 2000;
    private int openaiDailyRequestBudget = 9000;
    private String openaiBudgetRedisPrefix = "moderation:openai:requests";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getReportQuarantineThreshold() {
        return reportQuarantineThreshold;
    }

    public void setReportQuarantineThreshold(int reportQuarantineThreshold) {
        this.reportQuarantineThreshold = reportQuarantineThreshold;
    }

    public String getBlocklistResource() {
        return blocklistResource;
    }

    public void setBlocklistResource(String blocklistResource) {
        this.blocklistResource = blocklistResource;
    }

    public String getBlocklistTerms() {
        return blocklistTerms;
    }

    public void setBlocklistTerms(String blocklistTerms) {
        this.blocklistTerms = blocklistTerms;
    }

    public String getOpenaiApiKey() {
        return openaiApiKey;
    }

    public void setOpenaiApiKey(String openaiApiKey) {
        this.openaiApiKey = openaiApiKey;
    }

    public boolean isOpenaiEnabled() {
        return openaiEnabled;
    }

    public void setOpenaiEnabled(boolean openaiEnabled) {
        this.openaiEnabled = openaiEnabled;
    }

    public String getOpenaiModel() {
        return openaiModel;
    }

    public void setOpenaiModel(String openaiModel) {
        this.openaiModel = openaiModel;
    }

    public String getOpenaiBaseUrl() {
        return openaiBaseUrl;
    }

    public void setOpenaiBaseUrl(String openaiBaseUrl) {
        this.openaiBaseUrl = openaiBaseUrl;
    }

    public String getOpenaiCategoryBlocklist() {
        return openaiCategoryBlocklist;
    }

    public void setOpenaiCategoryBlocklist(String openaiCategoryBlocklist) {
        this.openaiCategoryBlocklist = openaiCategoryBlocklist;
    }

    public int getOpenaiTimeoutMillis() {
        return openaiTimeoutMillis;
    }

    public void setOpenaiTimeoutMillis(int openaiTimeoutMillis) {
        this.openaiTimeoutMillis = openaiTimeoutMillis;
    }

    public int getOpenaiDailyRequestBudget() {
        return openaiDailyRequestBudget;
    }

    public void setOpenaiDailyRequestBudget(int openaiDailyRequestBudget) {
        this.openaiDailyRequestBudget = openaiDailyRequestBudget;
    }

    public String getOpenaiBudgetRedisPrefix() {
        return openaiBudgetRedisPrefix;
    }

    public void setOpenaiBudgetRedisPrefix(String openaiBudgetRedisPrefix) {
        this.openaiBudgetRedisPrefix = openaiBudgetRedisPrefix;
    }
}
