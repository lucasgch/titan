# Robo BT_7274

Robôs desenvolvidos como parte da disciplina de Introdução à Computação do 1º Período de Análise e Desenvolvimento de Sistemas do IFSC - Campus São José.

**Orientação**: [Diego Medeiros](https://github.com/diegomedeiros-IFSC)

**Equipe**:
- [Felipe Rezende](https://github.com/feliperezn)
- [Gustavo Niehues](https://github.com/GustavoNiehues)
- [Lucas de Godoy Chicarelli](https://github.com/lucasgch)
- [Mauricio Telles Silva](https://github.com/MauricioTellesSilva)

## Como baixar e instalar o robocode

Acesse o repositório oficial e faça o download da útima versão: [https://robocode.sourceforge.io/](https://robocode.sourceforge.io/)

Instale o robocode:
```shell
java -jar robocode-1.10.1-setup.jar 
```

Entre na pasta e execute:

```shell
cd robocode
./robocode.sh
```

## Como contribuir

Clone o repositório

`git clone https://github.com/lucasgch/titan`

Crie uma branch com o seu nome ou o nome do seu robô

`git branch checkout -b nomedabranch`

Se você é um contribuidor deste repositório, faça o push das alterações para o repositório.

`git push --set-upstream origin lucas`

Se você não é um contribuir, mas enviar um robô ou uma contribuição, envie um Pull Request.

# BT-7274 (Versão Elite) 🤖🚀
### Robô Avançado para Robocode (AdvancedRobot)

O **BT-7274** é um robô de combate de alta densidade computacional desenvolvido para a plataforma Robocode. Ele utiliza uma arquitetura híbrida focada em **Mira Extrema Multi-Algorítmica** combinada com sistemas de defesa baseados em **Wave Surfing** e movimentação preditiva orbital (**MRM / ARIMA**). O robô é capaz de se adaptar dinamicamente ao comportamento do adversário em tempo real e reter o aprendizado entre os rounds através de memória persistente estática.

---

## 🧠 1. Sistema de Seleção de Movimentação (Engine de Evasão Híbrida)

O BT-7274 gerencia seu posicionamento através de um sistema de pesos de confiança dinâmicos (`confiancaSurfing` e `confiancaMRM`), alternando ou fundindo quatro motores de movimentação diferentes de acordo com o cenário da arena e o perfil classificado do inimigo:

### Perfis de Movimentação e Modos de Operação:
* **Modo Multi-Wave Surfing (Melee Geral):** Ativado automaticamente quando há mais de 3 robôs ativos no campo. O motor amplifica em 2.5x o peso de atração e repulsão de sombras (*Shadow Waves*), rastreando múltiplos projéteis simultaneamente no ar.
* **Modo Wave Surfing Focado (1v1 Anti-Surfer):** Ativado contra oponentes avançados classificados como *Surfers*. O robô calcula de forma preditiva o risco de evasão baseado em 47 Bins de GuessFactor. Ele simula turnos futuros para frente, para trás e parado (`preverRiscoMovimento`) para se mover em direção ao menor risco acumulado.
* **Modo ARIMA Dodging / MRM Predador (1v1 Anti-NonSurfer):** Se o oponente não possuir esquiva complexa (*non-surfer*), o robô desativa completamente o Wave Surfing (`confiancaSurfing = 0.0`). Ele assume uma movimentação orbital agressiva e preditiva a uma distância ideal de 300px, aplicando um multiplicador de 3.0x no motor de esquiva ARIMA para encurralar o inimigo.
* **Modo Anti-Clinger (Defesa contra Colisores):** Ativado instantaneamente se o adversário colidir fisicamente com o BT-7274 (`onHitRobot`) ou for identificado com comportamento colisor. O sistema desativa as ondas de surfing (`pesoS = 0.0`) e assume 100% de foco no motor MRM/ARIMA, forçando uma órbita rígida a 200px para empurrar e manter distância do oponente.
* **Wall Hump Fix (Fuga de Paredes):** Quando colide com os limites da arena (`onHitWall`), o robô inverte instantaneamente seus vetores laterais (`direcaoLateral` e `moveDirection1v1`), define o ponto de destino temporário (`pontoAlvo`) exatamente no centro geométrico do mapa e aplica um "tranco" mecânico reverso para escapar do travamento físico.

---

## 🔫 2. Sistema de Seleção de Arma (Arsenal de Virtual Guns)

O coração ofensivo do robô baseia-se em um barramento de **9 Virtual Guns (Armas Virtuais)** executadas de forma paralela. Cada vez que o robô atira, uma `Onda` virtual monitora o desempenho teórico de cada um dos 9 algoritmos de mira. O robô armazena as estatísticas de disparos reais (`disparosReaisVG`) e acertos reais (`acertosReaisVG`) para calcular a precisão de cada módulo.

### O Arsenal das 9 Virtual Guns (VGs):
1. **Auxiliar (0):** Algoritmo de fallback estatístico de suporte.
2. **ARMA (1):** Mira Analítica / Preditiva de alta frequência.
3. **ARIMA (2):** Mira matemática baseada em regressão linear de séries temporais.
4. **Rede Neural (3):** IA preditiva de padrões comportamentais de aceleração e ângulo.
5. **Dynamic Clustering (4):** Agrupamento dinâmico de dados baseado em estados semelhantes.
6. **Anti-Trem (5):** Filtro de mira especializado em anular robôs com movimentos oscilatórios e trepidações.
7. **Média GF (6):** Mira balanceada por médias históricas de GuessFactor.
8. **KNN Pesado (7):** Algoritmo de K-Nearest Neighbors associado a um banco multidimensional de até 30.000 registros de características físicas do alvo (`featuresKNN`).
9. **GunWave GF (8):** Sistema analítico com resolução ultra-elevada expandida para 101 BINS e simulação de até 800 pontos previstos.

### Regras de Transição e Travamento de Armas (*Locks*):
O robô possui um tomador de decisão heurístico que escolhe a melhor arma com base no perfil do alvo, mas aplica travas rígidas (*locks*) automáticas sob as seguintes circunstâncias:
* **Lock Anti-Surfer Adaptativo:** Contra robôs classificados como *Surfers*, a inteligência filtra e alterna dinamicamente apenas entre os três módulos de maior letalidade contra esquivas complexas: **KNN Pesado**, **Anti-Trem** e **GunWave GF**.
* **Lock Anti-Clinger:** Se o alvo for identificado como grudador (`ehClinger`), o robô trava rigidamente na **ARMA Analítica**, que possui cálculo telemétrico imediato para alvos muito próximos.
* **Lock Anti-NonSurfer:** Em duelos de fim de partida (1v1) contra alvos simples, o sistema trava o canhão na **ARMA Preditiva**, maximizando a taxa de dano por turno.
* **Lock de Baixa Precisão:** Se o robô disparar mais de 15 vezes e sua taxa de acerto global cair abaixo de 10%, ele entra em modo de emergência ofensiva, forçando uma alternância cíclica das armas para quebrar a previsibilidade.
* **Lock Vingança:** Se o histórico registrar uma sequência superior a 5 derrotas seguidas contra um oponente específico (`derrotasSeguidas > 5`), o robô ativa o bloqueio de contingência, ignorando a seleção natural e alternando agressivamente os perfis de mira a cada turno para furar a defesa inimiga.

---

## 💾 3. Persistência de Estado (Memória Estática)

Para garantir que o BT-7274 não precise remapear o adversário a cada início de round, ele utiliza estruturas de memória persistente estática (`static HashMap`) que retêm as assinaturas descobertas ao longo de toda a partida:
* `historicoArmaPorInimigo`: Salva o ID da arma virtual mais bem-sucedida contra cada oponente.
* `historicoSurferPorInimigo` / `historicoBasicoPorInimigo` / `historicoIntermediarioPorInimigo`: Mantém gravada a classificação comportamental exata de cada robô.
* `derrotasSeguidas`: Rastreia o saldo de rounds perdidos para ativar gatilhos como o *Lock Vingança*.

---

## 📜 4. Estrutura de Código e Lista de Funções

### Classe Principal (`BT_7274`)
Gere os estados globais do robô, as respostas do sistema físico aos eventos do Robocode, o HUD gráfico e as rotinas de decisão estratégica.

* `BT_7274()`: **Construtor.** Inicializa o motor de movimento secundário e aloca as coleções estruturadas para os pontos de simulação espacial.
* `run()`: Lógica de execução principal e contínua do ciclo de vida do robô. Define as configurações de cores da carcaça/radar, desconecta o giro do radar/canhão da base e roda o loop infinito de iteração de turnos.
* `onScannedRobot(ScannedRobotEvent e)`: Evento disparado continuamente quando o radar detecta um robô inimigo. É o núcleo analítico: atualiza dados telemétricos, registra histórico de velocidades, escolhe a melhor arma do arsenal de Virtual Guns e gerencia as ordens de disparo e travamento de radar.
* `onPaint(Graphics2D g)`: Renderiza toda a interface gráfica de telemetria na tela de simulação. Desenha o status atual do robô, as taxas de acerto individuais de cada uma das 9 Virtual Guns, linhas laser de mira contínua, caminhos de evasão e predição de posições futuras do adversário com quadrados amarelos.
* `onHitWall(HitWallEvent e)`: Trata colisões físicas contra as paredes. Inverte o sentido de movimento lateral, força um deslocamento reverso para escapar do travamento mecânico e reposiciona o alvo de fuga temporariamente no centro do campo de batalha.
* `onHitRobot(HitRobotEvent e)`: Evento acionado quando ocorre colisão direta com outro robô. Ativa instantaneamente o flag de detecção de inimigos do tipo `Clinger` (grudadores) para forçar um distanciamento defensivo imediato e a alteração dos motores de movimento e armas.
* `executarSurfing()`: Função central da defesa do robô. Avalia todas as ondas de tiros inimigos em aproximação, calcula os tempos de voo dos projéteis adversários e decide o melhor vetor de esquiva. Possui salvaguardas para desativação inteligente em cenários específicos de 1v1 contra alvos simples.
* `atualizarCaminhoVisual(OndaInimiga ondaPrimaria, int direcaoAcao)`: Atualiza a lista interna de coordenadas cartesianas do caminho ideal para que o sistema de pintura gráfica exiba a linha contínua de evasão preditiva na tela.
* `preverRiscoMovimento(OndaInimiga ondaPrimaria, int direcaoAcao)`: Realiza uma simulação hipotética passo a passo do robô movendo-se para frente, para trás ou permanecendo estático. Calcula matematicamente o risco cumulativo de cada escolha com base nos bins de GuessFactor mais atingidos pelo oponente.

### Classe Interna Estática `Utilitario`
Agrupa funções matemáticas puras e algoritmos geométricos compartilhados por todo o sistema.

* `limitar(double valor, double min, double max)`: Clampa um valor numérico para que ele não ultrapasse as barreiras informadas. Essencial para evitar que o robô tente se mover para fora dos limites físicos da arena.
* `aleatorioEntre(double min, double max)`: Retorna um número randômico decimal contido estritamente dentro do intervalo fornecido.
* `projetar(Point2D origem, double angulo, double distancia)`: Executa cálculos trigonométricos baseados em seno e cosseno para projetar e retornar uma nova coordenada (`Point2D.Double`) no plano cartesiano a partir de um ponto, uma direção radial e uma distância.
* `anguloAbsoluto(Point2D origem, Point2D alvo)`: Retorna o arco tangente real (ângulo absoluto em radianos) formado pela linha reta entre dois pontos no campo de batalha.
* `sinal(double v)`: Retorna `1` se o valor de entrada for positivo ou zero, e `-1` se o valor for negativo. Utilizado para simplificar multiplicações de orientação direcional.

### Classe Interna `OndaInimiga`
Representa a simulação matemática de um projétil disparado por um adversário se propagando pelo cenário como uma onda circular.

* `checarAcerto(double posX, double posY, long tempoAtual)`: Determina se o raio de expansão da onda de energia do tiro inimigo ultrapassou a posição atual do nosso robô. Em caso positivo, computa o bin correspondente no histórico estatístico (geral ou 1v1 dedicado) e limpa a onda da fila de processamento.
* `obterBin(double alvoX, double alvoY)`: Calcula o ângulo de deslocamento relativo do robô em relação à trajetória retilínea original do tiro inimigo e mapeia esse desvio para um índice numérico (Bin de 0 a 46) proporcional ao Ângulo de Escape Máximo.

### Classe Interna `Movimento_1VS1`
Gerenciador secundário de movimentação geométrica utilizado como fallback contra robôs mais básicos ou em situações controladas de duelo individual.

* `Movimento_1VS1(AdvancedRobot _robô)`: Construtor. Vincula a instância principal para controle dos eixos de direção.
* `onScannedRobot(ScannedRobotEvent e)`: Implementa um algoritmo orbital tático clássico. Calcula uma trajetória circular ao redor do inimigo que se ajusta em relação às paredes do mapa para evitar encurralamentos.

### Classe Interna `Onda`
Estende a classe `Condition` do Robocode. Rastreia as ondas geradas pelos **nossos próprios disparos** para pontuar e validar a assertividade de cada uma das 9 Virtual Guns.

* `Onda(AdvancedRobot _robô)`: Construtor que inicializa as propriedades da onda do disparo do BT-7274, salvando o ponto de partida do canhão e a assinatura de comportamento do inimigo naquele instante.
* `test()`: Método avaliador assíncrono disparado automaticamente pelo Robocode a cada turno. Monitora a expansão do tiro até o alvo. Quando a onda intercepta o oponente, ela calcula qual bin GuessFactor de 101 posições seria o real, pontua com pesos as armas virtuais que previram aquele bin com sucesso e limpa o evento customizado da memória.
* `registrarMiraKNNPesado(Robo inimigo, Robo meuRobo)`: Extrai as características de estado atuais (*features*) do inimigo, correlaciona com o bin real atingido pela onda e insere no banco de dados multidimensional do algoritmo KNN para consultas de mira futuras.
## 🏆 Resultados da Batalha

* **Batalha Melee**: 🥇 1º Lugar (11 robôs)
* **Batalha Top 3**: 🥈 2º Lugar (3 robôs)
