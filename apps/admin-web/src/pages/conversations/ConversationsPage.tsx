import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { RiDiscussLine, RiEqualizerLine, RiShieldCheckLine } from '@remixicon/react';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Empty, EmptyHeader, EmptyTitle } from '@/components/ui/empty';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { CatalogError } from '@/pages/lessons/CatalogFeedback';
import { fetchAdminChatSummary } from '@/pages/overview/admin-ops.api';

export function ConversationsPage() {
  const { t } = useTranslation('admin');

  const chatQuery = useQuery({
    queryKey: ['admin-chat-summary'],
    queryFn: ({ signal }) => fetchAdminChatSummary(signal),
  });

  const data = chatQuery.data;

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">{t('conversationsConsole.title')}</h1>
          <p className="text-sm text-muted-foreground">{t('conversationsConsole.subtitle')}</p>
        </div>
        <Button asChild variant="outline" size="sm">
          <Link to="/settings/ai">
            <RiEqualizerLine aria-hidden="true" />
            {t('navigation.aiSettings')}
          </Link>
        </Button>
      </div>

      <Alert>
        <RiShieldCheckLine className="size-4" aria-hidden="true" />
        <AlertTitle>{t('conversationsConsole.privacyTitle')}</AlertTitle>
        <AlertDescription>{t('conversationsConsole.privacyDescription')}</AlertDescription>
      </Alert>

      {chatQuery.error ? (
        <CatalogError error={chatQuery.error} retry={() => void chatQuery.refetch()} />
      ) : null}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Card>
          <CardHeader className="pb-2">
            <CardDescription>{t('conversationsConsole.metrics.totalConversations')}</CardDescription>
          </CardHeader>
          <CardContent className="text-2xl font-semibold tabular-nums">
            {chatQuery.isPending ? <Skeleton className="h-7 w-16" /> : data?.totalConversations ?? 0}
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="pb-2">
            <CardDescription>{t('conversationsConsole.metrics.activeLearners')}</CardDescription>
          </CardHeader>
          <CardContent className="text-2xl font-semibold tabular-nums">
            {chatQuery.isPending ? <Skeleton className="h-7 w-16" /> : data?.activeLearners ?? 0}
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="pb-2">
            <CardDescription>{t('conversationsConsole.metrics.turnsToday')}</CardDescription>
          </CardHeader>
          <CardContent className="text-2xl font-semibold tabular-nums">
            {chatQuery.isPending ? <Skeleton className="h-7 w-16" /> : data?.userTurnsToday ?? 0}
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="pb-2">
            <CardDescription>{t('conversationsConsole.metrics.turns7d')}</CardDescription>
          </CardHeader>
          <CardContent className="text-2xl font-semibold tabular-nums">
            {chatQuery.isPending ? <Skeleton className="h-7 w-16" /> : data?.userTurns7d ?? 0}
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>{t('conversationsConsole.recentTitle')}</CardTitle>
          <CardDescription>{t('conversationsConsole.recentHelp')}</CardDescription>
        </CardHeader>
        <CardContent>
          {chatQuery.isPending ? (
            <Skeleton className="h-40 w-full" />
          ) : !data || data.recentConversations.length === 0 ? (
            <Empty className="border py-8">
              <EmptyHeader>
                <RiDiscussLine className="size-8 text-muted-foreground" aria-hidden="true" />
                <EmptyTitle>{t('conversationsConsole.empty')}</EmptyTitle>
              </EmptyHeader>
            </Empty>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('conversationsConsole.columns.conversation')}</TableHead>
                  <TableHead>{t('conversationsConsole.columns.learner')}</TableHead>
                  <TableHead className="text-right">{t('conversationsConsole.columns.messages')}</TableHead>
                  <TableHead>{t('conversationsConsole.columns.updatedAt')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {data.recentConversations.map((conv) => (
                  <TableRow key={conv.conversationId}>
                    <TableCell className="font-mono text-xs">{conv.conversationId}</TableCell>
                    <TableCell>
                      <Link
                        className="font-mono text-xs underline underline-offset-3 hover:text-primary"
                        to={`/learners/${encodeURIComponent(conv.learnerId)}`}
                      >
                        {conv.learnerId}
                      </Link>
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{conv.messageCount}</TableCell>
                    <TableCell className="text-xs tabular-nums">
                      {new Date(conv.updatedAt).toLocaleString()}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
