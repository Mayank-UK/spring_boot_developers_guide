import { useParams } from 'react-router';
import { SectionWrapper } from '../../components/section-wrapper';
import { TextBlock } from '../../components/text-block';
import { CodeBlock } from '../../components/code-block';
import { DemoHost } from '../../components/demo-host/demo-host';
import { ErrorPanel } from '../../components/error-panel';
import type { ContentBlock } from '../../../domain/content/content';
import { useTopicDetail } from './topic_view.hooks';

/**
 * TopicView — THE generic topic screen: every topic served by the content API
 * renders through this one component (topic → numbered sections → blocks). It
 * owns no content of its own; the server is the single source of teaching
 * material and this view just maps each block kind onto its presenter
 * (text → TextBlock, code → CodeBlock, demo → DemoHost).
 */
export function TopicView() {
  const { topicId } = useParams<'topicId'>();
  const view = useTopicDetail(topicId);

  if (view.state === 'loading') {
    return (
      <div className="mx-auto flex w-full max-w-content flex-col gap-lg px-md py-xl">
        <p data-testid="topic-loading" className="text-body text-on-surface-variant">
          loading topic…
        </p>
        <div className="h-lg w-full animate-pulse rounded-md bg-surface-variant" />
        <div className="h-lg w-2/3 animate-pulse rounded-md bg-surface-variant" />
      </div>
    );
  }

  if (view.state === 'error') {
    return (
      <div className="mx-auto flex w-full max-w-content flex-col gap-lg px-md py-xl">
        <ErrorPanel subject="this topic" failure={view.failure} />
      </div>
    );
  }

  const { topic } = view;

  return (
    <article className="mx-auto flex w-full max-w-content flex-col gap-lg px-md py-xl">
      <header className="flex flex-col gap-sm">
        <h1 className="font-display text-display-sm text-on-surface">{topic.title}</h1>
        {topic.subtitle !== undefined && (
          <p className="text-title-sm text-on-surface-variant">{topic.subtitle}</p>
        )}
      </header>

      {topic.sections.map((section, index) => (
        <SectionWrapper
          key={section.id}
          id={section.id}
          index={index + 1}
          title={section.title}
          lead={section.lead}
        >
          {section.blocks.map((block) => (
            <TopicBlock key={block.id} block={block} />
          ))}
        </SectionWrapper>
      ))}
    </article>
  );
}

/**
 * Maps one served block onto its presenter. The switch is exhaustive over the
 * ContentBlock union — the `never` guard makes adding a block kind without a
 * renderer a compile-time error instead of a silent blank.
 */
function TopicBlock({ block }: { block: ContentBlock }) {
  switch (block.kind) {
    case 'text':
      return <TextBlock>{block.body}</TextBlock>;
    case 'code':
      return (
        <CodeBlock
          code={block.code}
          language={block.language}
          caption={block.caption}
          source={block.source}
        />
      );
    case 'demo':
      return (
        <DemoHost
          demoId={block.demoId}
          title={block.title}
          description={block.description}
        />
      );
    default: {
      const unhandled: never = block;
      return (
        <p className="text-body-sm text-on-surface-variant">
          Unsupported block kind: {JSON.stringify(unhandled)}
        </p>
      );
    }
  }
}

export default TopicView;
