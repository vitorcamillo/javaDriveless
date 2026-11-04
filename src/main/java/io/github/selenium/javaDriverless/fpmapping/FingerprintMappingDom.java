package io.github.selenium.javaDriverless.fpmapping;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/** Builds a browser-side, visible-node DOM map for a site contract. */
final class FingerprintMappingDom {
    private static final ObjectMapper JSON = new ObjectMapper();

    private FingerprintMappingDom() {
    }

    static String captureScript(String id) {
        FingerprintSiteContracts.Site contract = FingerprintSiteContracts.site(id);
        if (contract == null) {
            return "JSON.stringify({})";
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        for (FingerprintSiteContracts.RequiredDom field : contract.requiredDom) {
            fields.put(field.id, Map.of(
                    "terms", field.terms == null ? List.of() : field.terms,
                    "selectors", field.selectors == null ? List.of() : field.selectors,
                    "matchPolicy", field.matchPolicy == null ? "terms" : field.matchPolicy));
        }
        try {
            String payload = JSON.writeValueAsString(fields);
            return """
                    JSON.stringify((() => {
                      const fields = %s;
                      const clean = value => (value || '').replace(/\\s+/g, ' ').trim();
                      const visible = el => {
                        if (!el || ['HTML','BODY','SCRIPT','STYLE','NOSCRIPT','TEMPLATE'].includes(el.tagName)) return false;
                        const style = getComputedStyle(el);
                        const rect = el.getBoundingClientRect();
                        return style.display !== 'none' && style.visibility !== 'hidden'
                          && style.opacity !== '0' && rect.width > 0 && rect.height > 0;
                      };
                      const path = el => {
                        if (el.id) return '#' + CSS.escape(el.id);
                        const parts = [];
                        for (let node = el; node && node !== document.body && parts.length < 5; node = node.parentElement) {
                          let part = node.tagName.toLowerCase();
                          if (node.classList.length) part += '.' + Array.from(node.classList).slice(0, 2).map(CSS.escape).join('.');
                          parts.unshift(part);
                        }
                        return parts.join(' > ');
                      };
                      const generic = 'h1,h2,h3,h4,h5,strong,th,td,dt,dd,label,output,[role=status],[role=alert],[data-testid],[id],main p,main li,main div[class],section p,section li,section div[class]';
                      const out = {};
                      for (const [id, spec] of Object.entries(fields)) {
                        const selectors = spec.selectors && spec.selectors.length ? spec.selectors : [generic];
                        const candidates = [];
                        for (const selector of selectors) {
                          try { candidates.push(...document.querySelectorAll(selector)); } catch (_) {}
                        }
                        const terms = (spec.terms || []).map(clean).filter(Boolean);
                        const hits = Array.from(new Set(candidates)).filter(visible).map(el => ({
                          el, text: clean((el.dataset && el.dataset.raw) || el.innerText || el.textContent)
                        })).filter(item => item.text && item.text.length <= 600 &&
                          (spec.matchPolicy === 'nonEmpty'
                            || (spec.matchPolicy === 'numeric' && /^-?(?:\\d+(?:\\.\\d+)?|\\.\\d+)$/.test(item.text))
                            || (spec.matchPolicy === 'hex32' && /^(?:[✔✓]\\s*)?[a-f0-9]{32}$/i.test(item.text))
                            || (spec.matchPolicy === 'hex8' && /^(?:[✔✓]\\s*)?[a-f0-9]{8}$/i.test(item.text))
                            || (spec.matchPolicy === 'canvasHash' && /^CVS-[0-9A-F]{8}-[0-9A-F]{1,4}$/i.test(item.text))
                            || (spec.matchPolicy === 'fontMetricsReport'
                              && /^\\d+ fonts and \\d+ unique metrics found$/i.test(item.text))
                            || terms.some(term =>
                            item.text.toLocaleLowerCase().includes(term.toLocaleLowerCase())
                          ))).sort((a, b) => a.text.length - b.text.length);
                        const hit = hits[0];
                        if (hit) out[id] = {matched:true, tag:hit.el.tagName.toLowerCase(), selector:path(hit.el), text:hit.text};
                      }
                      return out;
                    })())
                    """.formatted(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize DOM contract for " + id, e);
        }
    }

    static Map<String, Object> visibleLineFallback(String id, String body) {
        FingerprintSiteContracts.Site contract = FingerprintSiteContracts.site(id);
        if (contract == null || body == null) return Map.of();
        List<String> lines = FingerprintMappingText.nonEmptyLines(body);
        Map<String, Object> out = new LinkedHashMap<>();
        for (FingerprintSiteContracts.RequiredDom field : contract.requiredDom) {
            String hit = "";
            for (int i = 0; i < lines.size() && hit.isBlank(); i++) {
                StringBuilder window = new StringBuilder(lines.get(i));
                for (int j = i + 1; j < Math.min(lines.size(), i + 3) && window.length() < 600; j++) {
                    window.append(' ').append(lines.get(j));
                }
                String candidate = window.toString();
                if (field.terms.stream().anyMatch(term -> candidate.toLowerCase(Locale.ROOT)
                        .contains(term.toLowerCase(Locale.ROOT)))) {
                    hit = candidate;
                }
            }
            if (!hit.isBlank()) {
                out.put(field.id, Map.of("matched", true, "tag", "text-line",
                        "selector", "visible-body-line", "text", hit));
            }
        }
        return out;
    }
}
