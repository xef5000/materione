import { describe, expect, it } from "vitest";
import { MAX_INPUT_TOKENS } from "../src/config";
import { ALLOWLIST, DEFAULT_MODEL_ID } from "../src/models";

const FORBIDDEN_TIER_PATTERN = /opus|sonnet|fable|gpt-5|o1|o3|pro/i;

describe("model allowlist price-tier ban", () => {
  it("contains DEFAULT_MODEL_ID and is non-empty", () => {
    const entries = Object.values(ALLOWLIST);
    expect(entries.length).toBeGreaterThan(0);
    expect(ALLOWLIST[DEFAULT_MODEL_ID]).toBeDefined();
  });

  it("enforces cheap short-context price ceilings and blocks frontier/long-context tiers", () => {
    for (const model of Object.values(ALLOWLIST)) {
      expect(model.inputPicosPerToken).toBeLessThanOrEqual(500_000); // <= $0.50 / 1M
      expect(model.cacheWritePicosPerToken).toBeLessThanOrEqual(625_000); // <= $0.625 / 1M
      expect(model.outputPicosPerToken).toBeLessThanOrEqual(2_500_000); // <= $2.50 / 1M
      expect(MAX_INPUT_TOKENS).toBeLessThan(model.longContextThresholdTokens);
      expect(model.id).not.toMatch(FORBIDDEN_TIER_PATTERN);
      expect(model.catalogId).not.toMatch(FORBIDDEN_TIER_PATTERN);
    }
  });
});
