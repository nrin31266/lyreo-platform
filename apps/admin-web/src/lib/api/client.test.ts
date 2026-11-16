import { AxiosError, AxiosHeaders, CanceledError } from "axios";
import { afterEach, expect, it, vi } from "vitest";
import { api, http, ApiError } from "./client";
vi.mock("@/lib/oidc/client", () => ({
  accessToken: vi.fn().mockResolvedValue("dev-test-token"),
}));
const adapter = http.defaults.adapter;
afterEach(() => {
  http.defaults.adapter = adapter;
});
it("attaches the current token, preserves cancellation and handles an empty response", async () => {
  const controller = new AbortController();
  http.defaults.adapter = async (config) => {
    expect(config.headers.get("Authorization")).toBe("Bearer dev-test-token");
    expect(config.signal).toBe(controller.signal);
    return {
      status: 204,
      statusText: "No Content",
      headers: new AxiosHeaders(),
      config,
      data: undefined,
    };
  };
  expect(
    await api("/example", { method: "DELETE", signal: controller.signal }),
  ).toBeUndefined();
});
it("preserves safe Problem Details and field errors", async () => {
  http.defaults.adapter = async (config) => {
    throw new AxiosError(
      "Request failed",
      "ERR_BAD_REQUEST",
      config,
      undefined,
      {
        status: 400,
        statusText: "Bad Request",
        headers: new AxiosHeaders(),
        config,
        data: {
          detail: "Invalid title",
          code: "VALIDATION_FAILED",
          correlationId: "request-a",
          errors: [{ field: "title", message: "Required" }],
        },
      },
    );
  };
  await expect(api("/example")).rejects.toMatchObject({
    status: 400,
    code: "VALIDATION_FAILED",
    correlationId: "request-a",
    errors: [{ field: "title", message: "Required" }],
  });
});
it("does not expose raw non-Problem responses and does not turn cancellation into an API error", async () => {
  http.defaults.adapter = async (config) => {
    throw new AxiosError("internal", "ERR_BAD_RESPONSE", config, undefined, {
      status: 500,
      statusText: "Failure",
      headers: new AxiosHeaders(),
      config,
      data: "private upstream trace",
    });
  };
  await expect(api("/example")).rejects.toEqual(
    expect.objectContaining({ message: "Unable to complete the request" }),
  );
  http.defaults.adapter = async () => {
    throw new CanceledError();
  };
  try {
    await api("/example");
    throw Error("Expected cancellation");
  } catch (error) {
    expect(error).not.toBeInstanceOf(ApiError);
    expect(error).toBeInstanceOf(CanceledError);
  }
});
