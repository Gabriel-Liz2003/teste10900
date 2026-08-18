# GameDrop — Eventos

A aba Eventos mostra apresentações de games passadas e futuras, como State of Play, Gamescom, The Game Awards e eventos equivalentes quando estiverem cadastrados na IGDB.

## Dados

O APK não contém o Client Secret da Twitch/IGDB. O GitHub Actions usa `IGDB_CLIENT_ID` e `IGDB_CLIENT_SECRET` como Secrets, consulta a IGDB e atualiza `data/events-feed.json` a cada 6 horas.

O app consome esse JSON, converte o horário para o fuso do aparelho e mantém cache local por 6 horas.

## Destaques

Os jogos de cada apresentação podem ser rotulados como novo anúncio, gameplay, novo trailer, atualização ou jogo apresentado. A classificação é conservadora e usa os nomes dos vídeos associados pela IGDB, evitando afirmar que um jogo foi anunciado pela primeira vez sem evidência suficiente.
