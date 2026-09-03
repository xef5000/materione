package dev.materione.web;

/**
 * Host facade for the editor's candidate/published release channel. The web
 * module owns the RPC shape while the plugin owns player delivery.
 */
public interface DeliveryControl {

    Status status();

    boolean publish(String expectedCandidateSha256);

    record Status(String publishedSha256, String candidateSha256) {}
}
