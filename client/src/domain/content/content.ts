/**
 * Content domain models — the pure shapes of the guide's teaching content:
 * index (groups + topic summaries) and one topic's detail (sections → blocks).
 * They know nothing about JSON or HTTP; the data layer validates wire payloads
 * into these shapes (see data/content/content-dto.ts).
 *
 * Const-object + literal-union style throughout (no enums — erasable syntax):
 * `ContentBlockKind` exists so code that needs the kind *values* as data (the
 * DTO parser) can loop/compare without importing string literals ad hoc.
 */

/** The three block kinds the content API serves (as-const, not an enum). */
export const ContentBlockKind = {
  Text: 'text',
  Code: 'code',
  Demo: 'demo',
} as const;

export type ContentBlockKind = (typeof ContentBlockKind)[keyof typeof ContentBlockKind];

/* ---- content index (GET /api/content) ---- */

export interface TopicGroup {
  id: string;
  title: string;
  order: number;
}

export interface TopicSummary {
  id: string;
  title: string;
  subtitle?: string;
  group: string;
  order: number;
  sectionCount: number;
}

export interface ContentIndex {
  version: number;
  groups: TopicGroup[];
  topics: TopicSummary[];
}

/* ---- topic detail (GET /api/content/{topicId}) ---- */

export interface TextBlockData {
  kind: 'text';
  id: string;
  body: string;
}

export interface CodeBlockData {
  kind: 'code';
  id: string;
  language: string;
  caption?: string;
  code: string;
  /** Repo-root-relative path of the real source this snippet is quoted from. */
  source?: string;
}

export interface DemoBlockData {
  kind: 'demo';
  id: string;
  demoId: string;
  title: string;
  description?: string;
}

/** Discriminated union on `kind` — the topic renderer switches exhaustively. */
export type ContentBlock = TextBlockData | CodeBlockData | DemoBlockData;

export interface TopicSection {
  id: string;
  title: string;
  lead?: string;
  blocks: ContentBlock[];
}

export interface TopicDetail {
  version: number;
  id: string;
  title: string;
  subtitle?: string;
  group: string;
  order: number;
  sections: TopicSection[];
}
