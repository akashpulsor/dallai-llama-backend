package com.dalai.llama.tenant.leadmanagement.creatormail;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The links one ops mail carries, numbered in the order the mail shows them. Each becomes
 * {@code <base>/ops/c/<delivery>/<position>}, which counts the click and goes on to the link
 * (OpsMailTrackingController). With no delivery (a preview or a test) links stay plain. */
final class TrackedLinks {

    record Link(int position, String label, String url) {
    }

    private final String base;
    private final UUID deliveryId;
    private final List<Link> links = new ArrayList<>();

    private TrackedLinks(String base, UUID deliveryId) {
        this.base = base;
        this.deliveryId = deliveryId;
    }

    static TrackedLinks forDelivery(String publicApiBaseUrl, UUID deliveryId) {
        return new TrackedLinks(publicApiBaseUrl, deliveryId);
    }

    static TrackedLinks none() {
        return new TrackedLinks(null, null);
    }

    /** The address the mail shows for {@code url}; the same link twice is one tracked link. */
    String to(String url, String label) {
        if (deliveryId == null) return url;
        Link link = links.stream().filter(l -> l.url().equals(url) && l.label().equals(label)).findFirst().orElseGet(() -> {
            Link added = new Link(links.size(), label.length() > 200 ? label.substring(0, 200) : label, url);
            links.add(added);
            return added;
        });
        return base + "/ops/c/" + deliveryId + "/" + link.position();
    }

    /** The open pixel for a designed mail; empty with no delivery. */
    String openPixelHtml() {
        if (deliveryId == null) return "";
        return "<img src=\"" + base + "/ops/o/" + deliveryId + ".gif\" width=\"1\" height=\"1\" alt=\"\" style=\"display:block;border:0\">";
    }

    List<Link> links() {
        return List.copyOf(links);
    }
}
