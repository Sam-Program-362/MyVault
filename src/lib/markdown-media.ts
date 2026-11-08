export const MAX_IMAGE_BYTES = 2 * 1024 * 1024;
export const MAX_BACKUP_MEDIA_BYTES = 16 * 1024 * 1024;
export const MAX_STORED_MEDIA_BYTES = 256 * 1024 * 1024;
export const MAX_IMPORT_JSON_BYTES = 4 * 1024 * 1024;

export type SupportedImageMime = "image/png" | "image/jpeg" | "image/gif" | "image/webp";

export interface MarkdownImageReference {
  destination: string;
  start: number;
  end: number;
}

function dimensions(width: number, height: number) {
  if (!Number.isInteger(width) || !Number.isInteger(height) || width < 1 || height < 1 || width > 12000 || height > 12000 || width * height > 40_000_000) {
    throw new Error("Image dimensions are unsupported (maximum 40 megapixels).");
  }
}

function u32be(bytes: Uint8Array, offset: number) {
  return ((bytes[offset] << 24) | (bytes[offset + 1] << 16) | (bytes[offset + 2] << 8) | bytes[offset + 3]) >>> 0;
}

function u24le(bytes: Uint8Array, offset: number) {
  return bytes[offset] | (bytes[offset + 1] << 8) | (bytes[offset + 2] << 16);
}

function crc32(bytes: Uint8Array, start: number, end: number) {
  let crc = 0xffffffff;
  for (let index = start; index < end; index++) {
    crc ^= bytes[index];
    for (let bit = 0; bit < 8; bit++) crc = (crc & 1) ? (0xedb88320 ^ (crc >>> 1)) : (crc >>> 1);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

/** Sniff and structurally validate only supported raster formats; never trust a filename or MIME hint. */
export function inspectImage(bytes: Uint8Array): { mime: SupportedImageMime; width: number; height: number } {
  if (bytes.length === 0 || bytes.length > MAX_IMAGE_BYTES) throw new Error("Images must be 2 MB or smaller.");
  const png = [137, 80, 78, 71, 13, 10, 26, 10];
  if (png.every((value, index) => bytes[index] === value)) {
    if (bytes.length < 45 || u32be(bytes, 8) !== 13 || String.fromCharCode(...bytes.slice(12, 16)) !== "IHDR") throw new Error("Malformed PNG image.");
    const width = u32be(bytes, 16);
    const height = u32be(bytes, 20);
    const bitDepth = bytes[24];
    const colorType = bytes[25];
    const validDepths: Record<number, number[]> = { 0: [1, 2, 4, 8, 16], 2: [8, 16], 3: [1, 2, 4, 8], 4: [8, 16], 6: [8, 16] };
    if (!validDepths[colorType]?.includes(bitDepth) || bytes[26] !== 0 || bytes[27] !== 0 || bytes[28] > 1) throw new Error("Malformed PNG image.");
    let offset = 8;
    let hasData = false;
    let hasEnd = false;
    while (offset + 12 <= bytes.length) {
      const size = u32be(bytes, offset);
      if (size > bytes.length - offset - 12) throw new Error("Malformed PNG image.");
      const type = String.fromCharCode(...bytes.slice(offset + 4, offset + 8));
      if (offset === 8 && (type !== "IHDR" || size !== 13)) throw new Error("Malformed PNG image.");
      const crcOffset = offset + size + 8;
      if (crc32(bytes, offset + 4, crcOffset) !== u32be(bytes, crcOffset)) throw new Error("Malformed PNG image checksum.");
      if (type === "IDAT" && size > 0) hasData = true;
      offset += size + 12;
      if (type === "IEND") {
        if (size !== 0 || offset !== bytes.length) throw new Error("Malformed PNG image.");
        hasEnd = true;
        break;
      }
    }
    if (!hasData || !hasEnd) throw new Error("Malformed PNG image.");
    dimensions(width, height);
    return { mime: "image/png", width, height };
  }

  if (bytes[0] === 0xff && bytes[1] === 0xd8) {
    let offset = 2;
    let width = 0;
    let height = 0;
    while (offset + 4 <= bytes.length) {
      while (bytes[offset] === 0xff) offset++;
      const marker = bytes[offset++];
      if (marker === 0xd9 || marker === 0xda) break;
      if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) continue;
      if (offset + 2 > bytes.length) break;
      const size = (bytes[offset] << 8) | bytes[offset + 1];
      if (size < 2 || offset + size > bytes.length) throw new Error("Malformed JPEG image.");
      if ([0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf].includes(marker)) {
        if (size < 7) throw new Error("Malformed JPEG image.");
        height = (bytes[offset + 3] << 8) | bytes[offset + 4];
        width = (bytes[offset + 5] << 8) | bytes[offset + 6];
      }
      offset += size;
    }
    if (!width || !height || bytes[bytes.length - 2] !== 0xff || bytes[bytes.length - 1] !== 0xd9) throw new Error("Malformed JPEG image.");
    dimensions(width, height);
    return { mime: "image/jpeg", width, height };
  }

  if (bytes.length >= 14 && (String.fromCharCode(...bytes.slice(0, 6)) === "GIF87a" || String.fromCharCode(...bytes.slice(0, 6)) === "GIF89a")) {
    const width = bytes[6] | (bytes[7] << 8);
    const height = bytes[8] | (bytes[9] << 8);
    if (bytes[bytes.length - 1] !== 0x3b) throw new Error("Malformed GIF image.");
    dimensions(width, height);
    return { mime: "image/gif", width, height };
  }

  if (bytes.length >= 30 && String.fromCharCode(...bytes.slice(0, 4)) === "RIFF" && String.fromCharCode(...bytes.slice(8, 12)) === "WEBP") {
    const declaredSize = (bytes[4] | (bytes[5] << 8) | (bytes[6] << 16) | (bytes[7] << 24)) >>> 0;
    if (declaredSize + 8 !== bytes.length) throw new Error("Malformed WebP image.");
    const kind = String.fromCharCode(...bytes.slice(12, 16));
    let width = 0;
    let height = 0;
    if (kind === "VP8X" && bytes.length >= 30) {
      width = 1 + u24le(bytes, 24);
      height = 1 + u24le(bytes, 27);
    } else if (kind === "VP8 " && bytes.length >= 30 && bytes[23] === 0x9d && bytes[24] === 0x01 && bytes[25] === 0x2a) {
      width = (bytes[26] | (bytes[27] << 8)) & 0x3fff;
      height = (bytes[28] | (bytes[29] << 8)) & 0x3fff;
    } else if (kind === "VP8L" && bytes.length >= 25 && bytes[20] === 0x2f) {
      width = 1 + bytes[21] + ((bytes[22] & 0x3f) << 8);
      height = 1 + (bytes[22] >> 6) + (bytes[23] << 2) + ((bytes[24] & 0x0f) << 10);
    }
    if (!width || !height) throw new Error("Malformed WebP image.");
    dimensions(width, height);
    return { mime: "image/webp", width, height };
  }

  throw new Error("Unsupported image. Use PNG, JPEG, GIF, or WebP; SVG is not supported.");
}

function isManagedDestination(destination: string) {
  return /^data:image\/(?:png|jpeg|jpg|gif|webp);base64,/i.test(destination) || /^\/?api\/media\/[0-9a-f-]{36}$/i.test(destination);
}

function closingAltBoundary(markdown: string, from: number) {
  for (let index = from; index < markdown.length - 1; index++) {
    if (markdown[index] === "\\") { index++; continue; }
    if (markdown[index] === "]" && markdown[index + 1] === "(") return index;
  }
  return -1;
}

/** Find only supported inline image destinations, outside fenced and inline code. Other Markdown stays byte-for-byte unchanged. */
export function findMarkdownImages(markdown: string): MarkdownImageReference[] {
  const found: MarkdownImageReference[] = [];
  let fenced: { marker: "`" | "~"; length: number } | null = null;
  let lineStart = 0;
  while (lineStart < markdown.length) {
    let lineEnd = markdown.indexOf("\n", lineStart);
    if (lineEnd < 0) lineEnd = markdown.length;
    const line = markdown.slice(lineStart, lineEnd);
    const fenceMatch = /^( {0,3})(`{3,}|~{3,})/.exec(line);
    if (fenced) {
      if (fenceMatch && fenceMatch[2][0] === fenced.marker && fenceMatch[2].length >= fenced.length && /^\s*$/.test(line.slice(fenceMatch[0].length))) fenced = null;
    } else if (fenceMatch) {
      fenced = { marker: fenceMatch[2][0] as "`" | "~", length: fenceMatch[2].length };
    } else {
      let index = lineStart;
      while (index < lineEnd) {
        if (markdown[index] === "`") {
          let runEnd = index + 1;
          while (runEnd < lineEnd && markdown[runEnd] === "`") runEnd++;
          const delimiter = markdown.slice(index, runEnd);
          const close = markdown.indexOf(delimiter, runEnd);
          if (close >= 0 && close < lineEnd) { index = close + delimiter.length; continue; }
          index = runEnd;
          continue;
        }
        if (markdown[index] === "!" && markdown[index + 1] === "[") {
          const boundary = closingAltBoundary(markdown, index + 2);
          if (boundary >= 0 && boundary < lineEnd) {
            let destinationStart = boundary + 2;
            let destinationEnd = destinationStart;
            while (destinationEnd < lineEnd && markdown[destinationEnd] !== ")" && !/\s/.test(markdown[destinationEnd])) destinationEnd++;
            if (markdown[destinationEnd] === ")") {
              const destination = markdown.slice(destinationStart, destinationEnd);
              if (isManagedDestination(destination)) found.push({ destination, start: destinationStart, end: destinationEnd });
              index = destinationEnd + 1;
              continue;
            }
          }
        }
        index++;
      }
    }
    lineStart = lineEnd + 1;
  }
  return found;
}

export function replaceMarkdownImages(markdown: string, replace: (destination: string) => string): string {
  const refs = findMarkdownImages(markdown);
  let result = markdown;
  for (const ref of refs.reverse()) result = result.slice(0, ref.start) + replace(ref.destination) + result.slice(ref.end);
  return result;
}

export function imageAltFromFilename(filename: string) {
  const base = filename.replace(/\\/g, "/").split("/").pop() ?? "";
  const withoutExtension = base.replace(/\.[^.]+$/, "");
  const readable = withoutExtension.replace(/[._-]+/g, " ").replace(/\s+/g, " ").trim();
  return readable || "Image";
}

export function escapeMarkdownAlt(alt: string) {
  return alt.trim().replace(/\\/g, "\\\\").replace(/\[/g, "\\[").replace(/\]/g, "\\]").replace(/\n/g, " ") || "Image";
}

export function parseDataImageUri(reference: string) {
  const match = /^data:(image\/(?:png|jpeg|jpg|gif|webp));base64,([A-Za-z0-9+/]*={0,2})$/i.exec(reference);
  if (!match || match[2].length > Math.ceil(MAX_IMAGE_BYTES / 3) * 4 + 4) throw new Error("Malformed or unsupported image data.");
  let decoded: string;
  try { decoded = atob(match[2]); } catch { throw new Error("Malformed base64 image data."); }
  const bytes = Uint8Array.from(decoded, (char) => char.charCodeAt(0));
  const image = inspectImage(bytes);
  const declaredMime = match[1].toLowerCase() === "image/jpg" ? "image/jpeg" : match[1].toLowerCase();
  if (image.mime !== declaredMime) throw new Error("Image data does not match its declared MIME type.");
  return { bytes, mime: image.mime };
}

export function dataUriFromBytes(bytes: Uint8Array, mime: SupportedImageMime) {
  if (bytes.length > MAX_IMAGE_BYTES) throw new Error("Images must be 2 MB or smaller.");
  const chunks: string[] = [];
  for (let offset = 0; offset < bytes.length; offset += 0x8000) chunks.push(String.fromCharCode(...bytes.subarray(offset, offset + 0x8000)));
  return `data:${mime};base64,${btoa(chunks.join(""))}`;
}
