const stats = [
  ['Lessons', '—', 'Admin-created content'],
  ['Jobs', '—', 'PostgreSQL durable queue'],
  ['Lexicon', '—', 'Wiktionary-backed'],
  ['TOEIC', '2019–2026', 'Imported dataset range'],
] as const;

export function Overview() {
  return (
    <section>
      <div className="eyebrow">Product workspace</div>
      <h1>Good evening, Lyreo team.</h1>
      <p className="lead">
        Build English learning content without hiding infrastructure behind magic. Every long
        workflow is inspectable, retryable and cancellable.
      </p>

      <div className="stat-grid">
        {stats.map(([label, value, note]) => (
          <article className="stat" key={label}>
            <small>{label}</small>
            <strong>{value}</strong>
            <span>{note}</span>
          </article>
        ))}
      </div>

      <div className="card">
        <h2>Architecture pulse</h2>
        <div className="pill-row">
          <span>Spring Modulith</span>
          <span>FastAPI AI runtime</span>
          <span>R2 artifacts</span>
          <span>PostgreSQL jobs</span>
          <span>No Kafka</span>
          <span>No Redis MVP</span>
        </div>
      </div>
    </section>
  );
}
