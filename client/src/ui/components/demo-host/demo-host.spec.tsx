import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { DemoHost } from './demo-host';
import { demoRegistry } from '../../demos/demo-registry';

describe('DemoHost', () => {
  it('renders the panel title and description around the demo body', () => {
    render(
      <DemoHost demoId="spring:app-info" title="Inspecting the running app" description="Ask the server about itself." />,
    );

    expect(
      screen.getByRole('heading', { level: 3, name: 'Inspecting the running app' }),
    ).toBeInTheDocument();
    expect(screen.getByText('Ask the server about itself.')).toBeInTheDocument();
  });

  it('renders the friendly not-wired-yet card for a demoId missing from the registry', () => {
    render(<DemoHost demoId="nope:not-built-yet" title="A demo that does not exist" />);

    const card = screen.getByTestId('demo-not-wired');
    expect(card).toBeInTheDocument();
    expect(card).toHaveTextContent('demo not wired yet');
    expect(card).toHaveTextContent('demoId: nope:not-built-yet');
    // It is a note, not an error.
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('the registry holds every landed demo — one lazy entry per demoId', () => {
    // Grows with each topic; unknown ids still degrade to the card above.
    expect(Object.keys(demoRegistry)).toEqual([
      'java:runtime-types',
      'jvm:threads',
      'jvm:streaming',
      'spring:app-info',
      'http:headers',
      'core:container',
      'aop:proxy',
      'aop:trace',
      'aop:self-invocation',
      'middleware:pipeline',
      'routing:explorer',
      'validation:arena',
      'reqres:inspector',
      'reqres:methods',
      'api:posts-workbench',
      'api:docs-link',
      'api:graphql-playground',
      'api:webhook-simulator',
      'api:grpc-console',
      'db:schema-explorer',
      'db:uuid',
      'db:sql-safety',
      'db:migrations',
      'db:sql-lab',
      'db:search',
      'db:race',
      'db:audit',
      'db:jpa-probe',
      'db:mongo-console',
      'redis:playground',
      'caching:lab',
      'msg:stream',
      'msg:outbox',
      'auth:sessions',
      'auth:api-keys',
      'auth:jwt',
      'auth:oauth',
      'auth:hashing',
      'auth:refresh',
      'authz:matrix',
      'security:lab',
      'security:pii',
      'files:workbench',
      'realtime:ticker',
      'realtime:chat',
      'i18n:negotiator',
      'jobs:console',
      'deps:manifest',
      'errors:explorer',
      'obs:viewer',
      'obs:trace',
      'arch:tracer',
      'arch:saga',
      'arch:eventstore',
      'testing:runner',
      'debug:stall',
      'env:inspector',
      'deploy:stack',
      'perf:bench',
      'reliability:breaker',
      'scaling:stateless',
      'reactive:compare',
      'thirdparty:hub',
      'best:checklist',
    ]);
  });
});
