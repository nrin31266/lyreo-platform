import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { I18nextProvider } from 'react-i18next';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { i18n } from '@/i18n';
import { ConversationsPage } from './ConversationsPage';
import {
  fetchSpeakingScenarios,
  previewSpeakingTurn,
  type SpeakingScenario,
} from './speaking.api';

vi.mock('./speaking.api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./speaking.api')>()),
  fetchSpeakingScenarios: vi.fn(),
  previewSpeakingTurn: vi.fn(),
}));

const sampleScenario: SpeakingScenario = {
  id: '70000000-0000-0000-0000-000000000801',
  code: 'CAFE_MORNING_RUSH',
  title: 'Ordering Espresso During Morning Rush',
  titleVi: 'Đặt cà phê buổi sáng giờ cao điểm',
  category: 'DAILY_LIFE',
  cefrLevel: 'A2',
  coverAssetId: null,
  aiRoleName: 'Maya (Barista)',
  aiPersona: 'Warm, efficient barista at an indie coffee bar',
  learnerRole: 'Commuter',
  settingDescription: 'Neighborhood coffee shop at 8:15 AM.',
  openingLine: 'Good morning! What can I get started for you today?',
  systemPrompt: 'Stay concise and ask natural follow-ups.',
  objectives: ['Order a drink with size and milk choice'],
  ttsVoice: 'af_heart',
  targetTurns: 6,
  estimatedMinutes: 4,
  accessMode: 'PUBLIC',
  requiredFeatureKey: null,
  status: 'PUBLISHED',
};

let client: QueryClient;

beforeEach(async () => {
  vi.resetAllMocks();
  await i18n.changeLanguage('en');
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  vi.mocked(fetchSpeakingScenarios).mockResolvedValue([sampleScenario]);
});

afterEach(() => {
  cleanup();
  client.clear();
});

it('renders speaking scenario details and executes live preview turn', async () => {
  vi.mocked(previewSpeakingTurn).mockResolvedValueOnce({
    assistantReply: 'Sure thing! Would you like hot or iced?',
    ttsVoice: 'af_heart',
    aiInvocationId: 'inv-1',
    provider: 'mock',
    latencyMs: 12,
    coachingTips: ['Mention milk preference naturally.'],
  });

  render(
    <QueryClientProvider client={client}>
      <I18nextProvider i18n={i18n}>
        <MemoryRouter>
          <ConversationsPage />
        </MemoryRouter>
      </I18nextProvider>
    </QueryClientProvider>,
  );

  expect(await screen.findByText('Ordering Espresso During Morning Rush')).toBeInTheDocument();
  expect(
    screen.getByText('Good morning! What can I get started for you today?'),
  ).toBeInTheDocument();

  fireEvent.change(screen.getByPlaceholderText('Type a learner response to test the AI roleplay…'), {
    target: { value: 'Hi, can I get a medium oat latte?' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Send turn' }));

  await waitFor(() =>
    expect(previewSpeakingTurn).toHaveBeenCalledWith(
      sampleScenario.id,
      [{ role: 'assistant', content: sampleScenario.openingLine }],
      'Hi, can I get a medium oat latte?',
    ),
  );
  expect(await screen.findByText('Sure thing! Would you like hot or iced?')).toBeInTheDocument();
});
