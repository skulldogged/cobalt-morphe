package dev.skulldogged.cobalt.extension;

final class CobaltResponse {
    final String url;
    final String filename;

    CobaltResponse(String url, String filename) {
        this.url = url;
        this.filename = filename;
    }
}
