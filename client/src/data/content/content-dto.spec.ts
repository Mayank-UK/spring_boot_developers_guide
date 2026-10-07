import { describe, expect, it } from 'vitest';
import {
  contentIndexDtoFromJson,
  topicDetailDtoFromJson,
  toContentIndex,
  toTopicDetail,
} from './content-dto';

/**
 * DTO parse spec — happy path (both endpoints, all three block kinds),
 * malformed payloads throw, and unknown wire fields are whitelisted away.
 */

const contentIndex = {
  version: 1,
  groups: [
    { id: 'fundamentals', title: 'Fundamentals', order: 1 },
    { id: 'apis', title: 'APIs', order: 2 },
  ],
  topics: [
    {
      id: 'spring-boot-fundamentals',
      title: 'Spring Boot Fundamentals',
      subtitle: 'From start.spring.io to a running app',
      group: 'fundamentals',
      order: 4,
      sectionCount: 8,
    },
    { id: 'routing', title: 'Routing', group: 'fundamentals', order: 5, sectionCount: 0 },
  ],
};

const topicDetail = {
  version: 1,
  id: 'spring-boot-fundamentals',
  title: 'Spring Boot Fundamentals',
  subtitle: 'From start.spring.io to a running app',
  group: 'fundamentals',
  order: 4,
  sections: [
    {
      id: 'sbt:anatomy',
      title: 'Anatomy of a controller',
      lead: 'The lead paragraph.',
      blocks: [
        { kind: 'text', id: 'b-text', body: 'The body of the text block.' },
        {
          kind: 'code',
          id: 'b-code',
          language: 'java',
          caption: 'the caption',
          code: 'record Hello(String name) {}',
          source: 'server/src/main/java/guide/Hello.java',
        },
        {
          kind: 'demo',
          id: 'b-demo',
          demoId: 'spring:app-info',
          title: 'Inspecting the running app',
          description: 'Ask the server about itself.',
        },
      ],
    },
    {
      id: 'sbt:terminal',
      title: 'Terminal companion',
      blocks: [],
    },
  ],
};

describe('contentIndexDtoFromJson', () => {
  it('parses the happy-path index: groups and topic summaries', () => {
    const dto = contentIndexDtoFromJson(contentIndex);

    expect(dto.version).toBe(1);
    expect(dto.groups).toHaveLength(2);
    expect(dto.groups[0]).toEqual({
      id: 'fundamentals',
      title: 'Fundamentals',
      order: 1,
    });
    expect(dto.topics[0]).toEqual({
      id: 'spring-boot-fundamentals',
      title: 'Spring Boot Fundamentals',
      subtitle: 'From start.spring.io to a running app',
      group: 'fundamentals',
      order: 4,
      sectionCount: 8,
    });
    // Optional fields resolve to undefined when the wire omits them.
    expect(dto.topics[1]?.subtitle).toBeUndefined();
  });

  it('parses an EMPTY index (the registry today) as a valid state', () => {
    const dto = contentIndexDtoFromJson({ version: 1, groups: [], topics: [] });
    expect(toContentIndex(dto)).toEqual({ version: 1, groups: [], topics: [] });
  });

  it('whitelists: unknown fields are dropped, known fields survive', () => {
    const dto = contentIndexDtoFromJson({
      ...contentIndex,
      futureField: 'not in the contract',
      topics: [
        { ...(contentIndex.topics[0] ?? {}), extra: true, id: 'one-topic' },
      ],
    });

    expect(dto.topics[0]?.id).toBe('one-topic');
    expect(JSON.stringify(dto)).not.toContain('futureField');
    expect(JSON.stringify(dto)).not.toContain('"extra"');
  });

  it.each([
    ['not an object', 'nope'],
    ['groups not an array', { version: 1, groups: 'nope', topics: [] }],
    ['missing topics', { version: 1, groups: [] }],
    ['topic summary missing sectionCount', { version: 1, groups: [], topics: [{ id: 'a', title: 'A', group: 'g', order: 1 }] }],
    ['group order NaN', { version: 1, groups: [{ id: 'g', title: 'G', order: Number.NaN }], topics: [] }],
  ])('throws on malformed index: %s', (_label, body) => {
    expect(() => contentIndexDtoFromJson(body)).toThrow();
  });
});

describe('topicDetailDtoFromJson', () => {
  it('parses the happy-path detail with all three block kinds, in order', () => {
    const dto = topicDetailDtoFromJson(topicDetail);
    const blocks = dto.sections[0]?.blocks ?? [];

    expect(blocks.map((block) => block.kind)).toEqual(['text', 'code', 'demo']);
    expect(blocks[0]).toEqual({ kind: 'text', id: 'b-text', body: 'The body of the text block.' });
    expect(blocks[1]).toEqual({
      kind: 'code',
      id: 'b-code',
      language: 'java',
      caption: 'the caption',
      code: 'record Hello(String name) {}',
      source: 'server/src/main/java/guide/Hello.java',
    });
    expect(blocks[2]).toEqual({
      kind: 'demo',
      id: 'b-demo',
      demoId: 'spring:app-info',
      title: 'Inspecting the running app',
      description: 'Ask the server about itself.',
    });
    expect(dto.sections[1]?.blocks).toEqual([]);
    expect(toTopicDetail(dto).id).toBe('spring-boot-fundamentals');
  });

  it.each([
    ['not an object', []],
    ['missing title', { version: 1, id: 'a', group: 'g', order: 1, sections: [] }],
    ['sections not an array', { version: 1, id: 'a', title: 'A', group: 'g', order: 1, sections: {} }],
    ['unknown block kind', { version: 1, id: 'a', title: 'A', group: 'g', order: 1, sections: [{ id: 's', title: 'S', blocks: [{ kind: 'video', id: 'b' }] }] }],
    ['code block missing code', { version: 1, id: 'a', title: 'A', group: 'g', order: 1, sections: [{ id: 's', title: 'S', blocks: [{ kind: 'code', id: 'b', language: 'java' }] }] }],
    ['demo block missing demoId', { version: 1, id: 'a', title: 'A', group: 'g', order: 1, sections: [{ id: 's', title: 'S', blocks: [{ kind: 'demo', id: 'b', title: 'T' }] }] }],
  ])('throws on malformed detail: %s', (_label, body) => {
    expect(() => topicDetailDtoFromJson(body)).toThrow();
  });

  it('whitelists: unknown keys inside blocks are dropped', () => {
    const dto = topicDetailDtoFromJson({
      ...topicDetail,
      futureTopLevel: true,
    });
    expect(JSON.stringify(dto)).not.toContain('futureTopLevel');
  });
});
