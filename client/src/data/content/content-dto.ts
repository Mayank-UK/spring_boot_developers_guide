import type {
  CodeBlockData,
  ContentBlock,
  ContentIndex,
  DemoBlockData,
  TextBlockData,
  TopicDetail,
  TopicGroup,
  TopicSection,
  TopicSummary,
} from '../../domain/content/content';
import {
  isRecord,
  optionalString,
  requiredArray,
  requiredNumber,
  requiredString,
} from './validate';

/**
 * ContentDTOs — the wire/transport shapes of the content API, responsible for
 * (de)serialization and mapping to the domain models (express-guide `PostDTO`
 * style). The wire format was designed to match the domain models 1:1; the DTO
 * interfaces exist as the seam where wire and domain may diverge.
 *
 * Every fromJson builds ONLY the fields listed below — unknown wire keys are
 * dropped (whitelist) — and throws on any shape violation (validate.ts), which
 * parseDto upgrades to an InvalidResponseException.
 */

export interface ContentIndexDto {
  version: number;
  groups: TopicGroup[];
  topics: TopicSummary[];
}

export interface TopicDetailDto {
  version: number;
  id: string;
  title: string;
  subtitle?: string;
  group: string;
  order: number;
  sections: TopicSection[];
}

/** Deserialize + validate the GET /api/content payload. */
export function contentIndexDtoFromJson(body: unknown): ContentIndexDto {
  if (!isRecord(body)) throw new Error('content index must be an object');
  return {
    version: requiredNumber(body, 'version'),
    groups: requiredArray(body, 'groups').map(topicGroupFromJson),
    topics: requiredArray(body, 'topics').map(topicSummaryFromJson),
  };
}

/** Deserialize + validate the GET /api/content/{topicId} payload. */
export function topicDetailDtoFromJson(body: unknown): TopicDetailDto {
  if (!isRecord(body)) throw new Error('topic detail must be an object');
  return {
    version: requiredNumber(body, 'version'),
    id: requiredString(body, 'id'),
    title: requiredString(body, 'title'),
    subtitle: optionalString(body, 'subtitle'),
    group: requiredString(body, 'group'),
    order: requiredNumber(body, 'order'),
    sections: requiredArray(body, 'sections').map(topicSectionFromJson),
  };
}

/** Convert a transport DTO into a domain model. */
export function toContentIndex(dto: ContentIndexDto): ContentIndex {
  return { version: dto.version, groups: dto.groups, topics: dto.topics };
}

/** Convert a transport DTO into a domain model. */
export function toTopicDetail(dto: TopicDetailDto): TopicDetail {
  return {
    version: dto.version,
    id: dto.id,
    title: dto.title,
    subtitle: dto.subtitle,
    group: dto.group,
    order: dto.order,
    sections: dto.sections,
  };
}

/* ---- nested payload validators (throw on shape violation) ---- */

function topicGroupFromJson(body: unknown): TopicGroup {
  if (!isRecord(body)) throw new Error('group must be an object');
  return {
    id: requiredString(body, 'id'),
    title: requiredString(body, 'title'),
    order: requiredNumber(body, 'order'),
  };
}

function topicSummaryFromJson(body: unknown): TopicSummary {
  if (!isRecord(body)) throw new Error('topic summary must be an object');
  return {
    id: requiredString(body, 'id'),
    title: requiredString(body, 'title'),
    subtitle: optionalString(body, 'subtitle'),
    group: requiredString(body, 'group'),
    order: requiredNumber(body, 'order'),
    sectionCount: requiredNumber(body, 'sectionCount'),
  };
}

function contentBlockFromJson(body: unknown): ContentBlock {
  if (!isRecord(body)) throw new Error('block must be an object');
  switch (body.kind) {
    case 'text': {
      const text: TextBlockData = {
        kind: 'text',
        id: requiredString(body, 'id'),
        body: requiredString(body, 'body'),
      };
      return text;
    }
    case 'code': {
      const code: CodeBlockData = {
        kind: 'code',
        id: requiredString(body, 'id'),
        language: requiredString(body, 'language'),
        caption: optionalString(body, 'caption'),
        code: requiredString(body, 'code'),
        source: optionalString(body, 'source'),
      };
      return code;
    }
    case 'demo': {
      const demo: DemoBlockData = {
        kind: 'demo',
        id: requiredString(body, 'id'),
        demoId: requiredString(body, 'demoId'),
        title: requiredString(body, 'title'),
        description: optionalString(body, 'description'),
      };
      return demo;
    }
    default:
      throw new Error(`unknown block kind "${String(body.kind)}"`);
  }
}

function topicSectionFromJson(body: unknown): TopicSection {
  if (!isRecord(body)) throw new Error('section must be an object');
  return {
    id: requiredString(body, 'id'),
    title: requiredString(body, 'title'),
    lead: optionalString(body, 'lead'),
    blocks: requiredArray(body, 'blocks').map(contentBlockFromJson),
  };
}
