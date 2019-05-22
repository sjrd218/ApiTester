package com.plugin.apitester;

/**
 * Http methods supported by the advanced webhook plugin
 *
 * Some methods don't allow a content body to be passed so we mark those to ensure correct plugin behavior
 */
public enum HttpMethod {
    GET(false),POST(true),PUT(true),HEAD(false),PATCH(true),OPTIONS(false),DELETE(false);

    private boolean allowBody;

    HttpMethod(boolean allowBody) {
        this.allowBody = allowBody;
    }

    public boolean isAllowBody() { return allowBody; };
}
