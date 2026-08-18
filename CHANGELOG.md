# Changelog

## 1.2.0 — 18/08/2026

- Adicionada a aba **Eventos** com próximos eventos e histórico de apresentações de games.
- Adicionada tela de detalhes do evento com data/hora local, livestream, jogos apresentados e vídeos vinculados.
- Adicionada classificação conservadora de destaques: novo anúncio, gameplay, novo trailer, atualização ou jogo apresentado.
- Adicionado feed de eventos baseado na IGDB, atualizado por GitHub Actions sem expor o Client Secret no APK.
- Adicionado cache local do feed de eventos por 6 horas e fallback offline.
- Aumentado o conjunto de testes JVM de 14 para 18.

## 1.1.0 — 17/08/2026

- Sincronização automática a cada 6 horas e atualização ao abrir o app.
- Adicionados jogos sem data definida (TBA).
- Adicionados trailers com fallback para busca no YouTube.
- Adicionada tradução automática de sinopses para PT-BR conforme o idioma do app.
