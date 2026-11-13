"use client";

import { useState, type ComponentProps } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

function MarkdownImage({ src, alt }: ComponentProps<"img">) {
  const [failedSource, setFailedSource] = useState<string | null>(null);
  const label = alt?.trim() || "Image";
  if (typeof src !== "string" || !src || failedSource === src) {
    return <span className="md-image-fallback" role="img" aria-label={`Image unavailable: ${label}`}>[Image unavailable: {label}]</span>;
  }
  return (
    // eslint-disable-next-line @next/next/no-img-element
    <img src={src} alt={label} loading="lazy" onError={() => setFailedSource(src)} />
  );
}

function MarkdownTable({ children, ...props }: ComponentProps<"table">) {
  return <div className="md-table-scroll"><table {...props}>{children}</table></div>;
}

export default function Markdown({ content }: { content: string }) {
  return (
    <div className="md-body">
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={{ img: MarkdownImage, table: MarkdownTable }}>{content || "*Nothing here yet.*"}</ReactMarkdown>
    </div>
  );
}
