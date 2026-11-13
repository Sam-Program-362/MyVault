/** Server-side Temporary Tags feature gate. Missing or non-literal values remain disabled. */
export function isTagStagingEnabled(value: string | undefined = process.env.TAG_STAGING_ENABLED): boolean {
  return value === "true";
}
