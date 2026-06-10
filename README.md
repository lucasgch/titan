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
* **Módulo 0: Auxiliar (Estatística de Backup)**
    * *Como funciona:* Atua como um buffer probabilístico bruto e sem segmentação complexa. Ele registra os ângulos gerais de maior incidência do inimigo ao longo de toda a partida.
    * *Foco Tático:* Serve como uma rede de segurança (*fail-safe*). É acionado nos primeiros segundos do Round 1 (quando os outros algoritmos complexos ainda não coletaram dados suficientes) ou se o robô sofrer um reset de dados induzido por falha de leitura telemétrica.

* **Módulo 1: ARMA (Analítica / Preditiva Direta)**
    * *Como funciona:* Executa cálculos geométricos puros baseados na trigonometria do vetor de movimento atual do alvo. Se o inimigo estiver fazendo uma curva, ele aplica uma projeção circular clássica; se estiver reto, uma projeção linear.
    * *Foco Tático:* Alvos simples, robôs lineares e oponentes da classe *Clinger* (colisores). Por ter tempo de processamento próximo de zero e resposta imediata, é letal em curtas distâncias, onde o tempo de voo da bala é menor que o tempo de reação de esquiva do inimigo.

* **Módulo 2: ARIMA (Previsão de Séries Temporais)**
    * *Como funciona:* Baseado no modelo matemático Autorregressivo Integrado de Médias Móveis. Trata o histórico de velocidade lateral e mudanças de direção do oponente como uma linha do tempo sequencial, tentando prever as próximas oscilações com base nas tendências e erros passados.
    * *Foco Tático:* Robôs que realizam curvas suaves e desacelerações previsíveis em arco. Ele consegue prever o exato momento em que um robô vai frear para não colidir com uma parede bem antes de a frenagem física acontecer.

* **Módulo 3: Rede Neural (Padrões Não-Lineares)**
    * *Como funciona:* Uma micro-rede neural artificial que recebe como entradas (*inputs*) a distância do alvo, velocidade angular, aceleração atual e tempo decorrido desde a última colisão de parede. A rede processa esses dados em camadas para cuspir o ângulo de disparo mais provável.
    * *Foco Tático:* Quebrar inteligências artificiais adversárias. É excelente para aprender e decodificar padrões de movimento customizados e complexos de robôs topo de linha que jogam de forma semi-previsível no médio prazo.

* **Módulo 4: Dynamic Clustering (Agrupamento Dinâmico)**
    * *Como funciona:* Fatia o estado atual da batalha em uma janela de dados multidimensionais. O algoritmo varre o histórico recente em busca de momentos em que a configuração espacial do mapa era parecida com a de agora e extrai o padrão de movimentação resultante desses grupos.
    * *Foco Tático:* Robôs de transição comportamental (aqueles que mudam de estratégia de esquiva no meio do combate dependendo da energia ou do tempo).

* **Módulo 5: Anti-Trem (Filtro Anti-Oscilação)**
    * *Como funciona:* Ignora micro-mudanças de alta frequência na velocidade do alvo. Robôs comuns costumam se confundir quando o inimigo aperta as teclas "frente" e "trás" freneticamente para fazer a mira analítica tremer. Esta arma aplica uma média móvel pesada (filtro passa-baixa), mirando estritamente no centro de massa real do movimento macro do oponente.
    * *Foco Tático:* Robôs com esquiva do tipo *Shaking* (Oscilatórios / Intermediários). Anula completamente a tentativa do inimigo de gerar ruído físico na mira.

* **Módulo 6: Média GF (Média Clássica de GuessFactor)**
    * *Como funciona:* Divide o Ângulo de Escape Máximo (MEA) do inimigo em um array indexado. Toda vez que uma onda passa pelo oponente, ela computa onde ele estava e adiciona um peso suave a essa estatística histórica.
    * *Foco Tático:* Estabilidade de longo prazo. Por não sofrer influência drástica de mudanças repentinas de direção de um único turno, esta arma garante uma taxa de acerto sólida contra robôs medianos ao longo de partidas longas (10+ rounds).

* **Módulo 7: KNN Pesado (K-Nearest Neighbors de Alta Densidade)**
    * *Como funciona:* É a artilharia pesada do robô. Mantém um banco de dados persistente em memória com capacidade para até 30.000 entradas de registros telemétricos (*features*). Ao mirar, ele localiza instantaneamente os *K* registros matematicamente mais próximos da situação atual do inimigo e dispara no ângulo que historicamente mais obteve sucesso contra aquela exata postura do alvo.
    * *Foco Tático:* Contra-ataque a robôs com defesa de *Wave Surfing Elite*. É capaz de mapear hábitos subconscientes do programador adversário (como a tendência de esquivar mais para a direita quando está perto de paredes).

* **Módulo 8: GunWave GF (Resolução Ultra-Elevada - 101 BINS)**
    * *Como funciona:* Enquanto uma mira GuessFactor convencional utiliza de 31 a 47 divisões angulares (Bins), este módulo estende a resolução para impressionantes 101 Bins analíticos. Além disso, o algoritmo projeta e simula até 800 pontos virtuais da trajetória do alvo no ar por onda disparada.
    * *Foco Tático:* Sniper de precisão cirúrgica a longas distâncias. Ideal para encontrar micropontos cegos e falhas de arredondamento em robôs inimigos que possuem movimentação defensiva extremamente precisa.

---
### Regras de Transição e Travamento de Armas (*Locks*):
O robô possui um tomador de decisão heurístico que escolhe a melhor arma com base no perfil do alvo, mas aplica travas rígidas (*locks*) automáticas sob as seguintes circunstâncias:
* **Lock Anti-Surfer Adaptativo:** Contra robôs classificados como *Surfers*, a inteligência filtra e alterna dinamicamente apenas entre os três módulos de maior letalidade contra esquivas complexas: **KNN Pesado**, **Anti-Trem** e **GunWave GF**.
* **Lock Anti-Clinger:** Se o alvo for identificado como grudador (`ehClinger`), o robô trava rigidamente na **ARMA Analítica**, que possui cálculo telemétrico imediato para alvos muito próximos.
* **Lock Anti-NonSurfer:** Em duelos de fim de partida (1v1) contra alvos simples, o sistema trava o canhão na **ARMA Preditiva**, maximizando a taxa de dano por turno.
* **Lock de Baixa Precisão:** Se o robô disparar mais de 15 vezes e sua taxa de acerto global cair abaixo de 10%, ele entra em modo de emergência ofensiva, forçando uma alternância cíclica das armas para quebrar a previsibilidade.
* **Lock Vingança:** Se o histórico registrar uma sequência superior a 5 derrotas seguidas contra um oponente específico (`derrotasSeguidas > 5`), o robô ativa o bloqueio de contingência, ignorando a seleção natural e alternando agressivamente os perfis de mira a cada turno para furar a defesa inimiga.

---

## 📊 3. Sistema de Classificação Dinâmica de Adversários

O BT-7274 não combate todos os inimigos da mesma forma. Ele possui um motor analítico de heurísticas comportamentais que avalia a movimentação e a reação do oponente para rotulá-lo em uma de **quatro classes estritas**. Essa classificação dita o comportamento imediato das engines de movimento e de mira:

### As Quatro Classes de Perfis:
1. **Clinger (Colisor / Rammer):**
   * *Gatilho de Identificação:* Ativado instantaneamente através do evento `onHitRobot` ou se a telemetria detectar o inimigo mantendo-se intencionalmente a distâncias críticas inferiores a 120px.
   * *Impacto Estratégico:* Desativa a engine de Wave Surfing e ativa 100% da movimentação orbital MRM/ARIMA a curta distância. Trava a mira na arma analítica direta.
2. **Surfer (Avançado / Esquiva por Ondas):**
   * *Gatilho de Identificação:* Identificado se o robô adversário exibir desacelerações repentinas ou reversões de marcha perfeitamente sincronizadas com o momento exato em que o BT-7274 dispara uma onda (`Onda.test()`), ou se as armas preditivas básicas mantiverem acertos próximos a zero.
   * *Impacto Estratégico:* Ativa a engine completa de Wave Surfing de 47 Bins com predição de risco em profundidade e restringe o canhão para operar estritamente via KNN Pesado ou GunWave GF.
3. **Intermediário (Random / Oscilatório / Trem):**
   * *Gatilho de Identificação:* Detectado quando o inimigo inverte a direção constantemente em intervalos muito curtos de tempo (ticks) sem se deslocar significativamente de sua área original, tentando confundir algoritmos lineares através de ruído físico.
   * *Impacto Estratégico:* Mantém o Wave Surfing ativo em nível moderado e força o sistema de armas a priorizar o algoritmo **Anti-Trem**, projetado especificamente para filtrar as oscilações periódicas e atingir o centro de massa do alvo.
4. **Básico (Linear / Circular / Não-Surfer):**
   * *Gatilho de Identificação:* Rótulo padrão atribuído a robôs que mantêm velocidades constantes em linhas retas ou trajetórias circulares previsíveis, ignorando completamente os disparos efetuados contra eles.
   * *Impacto Estratégico:* Ativa o *Override MRM Agressivo*, desligando o processamento do Wave Surfing para economizar energia do motor e assumindo uma órbita intimidadora a 300px. Trava o canhão na mira analítica/preditiva padrão para abatê-lo rapidamente.

---

## 💾 4. Persistência de Estado (Memória Estática)

Para garantir que o BT-7274 não precise remapear o adversário a cada início de round, ele utiliza estruturas de memória persistente estática (`static HashMap`) que retêm as assinaturas descobertas ao longo de toda a partida:
* `historicoArmaPorInimigo`: Salva o ID da arma virtual mais bem-sucedida contra cada oponente.
* `historicoSurferPorInimigo` / `historicoBasicoPorInimigo` / `historicoIntermediarioPorInimigo`: Mantém gravada a classificação comportamental exata de cada robô.
* `derrotasSeguidas`: Rastreia o saldo de rounds perdidos para ativar gatilhos como o *Lock Vingança*.

---

## 📜 5. Estrutura de Código e Lista de Funções

### Classe Principal (`BT_7274`)
Gere os estados globais do robô, as respostas do sistema físico aos eventos do Robocode, o HUD gráfico e as rotinas de decisão estratégica.

* `BT_7274()`: **Construtor.** Inicializa o motor de movimento secundário e aloca as coleções estruturadas para os pontos de simulação espacial.
* `run()`: Lógica de execução principal e contínua do ciclo de vida do robô. Define as configurações de cores da carcaça/radar, desconecta o giro do radar/canhão da base e roda o loop infinito de iteração de turnos.
* `onScannedRobot(ScannedRobotEvent e)`: Evento disparado continuamente quando o radar detecta um robô inimigo. É o núcleo analítico: atualiza dados telemétricos, registra histórico de velocidades, escolhe a melhor arma do arsenal de Virtual Guns e gerencia as ordens de disparo e travamento de radar.
* `onPaint(Graphics2D g)`: Renderiza toda a interface gráfica de telemetria na tela de simulação. Desenha o status atual do robô, as taxas de acerto individuais de cada uma das 9 Virtual Guns, linhas laser de mira contínua, caminhos de evasão e predição de posições futuras do adversário com quadrados amarelos.
* `onHitWall(HitWallEvent e)`: Trata colisões físicas contra as paredes. Inverte o sentido de movimento lateral, força um deslocamento reverso para escapar do travamento mecânico (`Wall Hump Fix`) e reposiciona o alvo de fuga temporariamente no centro do campo de batalha.
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
* `sinal(double v)`: Retorna `1` se o valor de entrada for positivo ou zero, e `-1` se o valor foi negativo.

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

## Relatório
### 1. Introdução
### 2. Objetivos da Atividade
### 3. Descrição da Atividade
#### 3.1 Processo de Programação do Robô
O robô do grupo, batizado de **BT_7274**, foi implementado em Java como um `AdvancedRobot` — a classe mais completa do Robocode, que permite controle independente da base, do canhão e do radar.

O desenvolvimento foi conduzido de forma iterativa e incremental: a cada sessão de trabalho, um ou mais membros do grupo implementavam uma nova funcionalidade, testavam localmente no Robocode e então integravam ao repositório via Git. Essa abordagem permitiu que o robô evoluísse progressivamente de uma estrutura básica para um sistema sofisticado ao longo das semanas.

As principais funcionalidades desenvolvidas foram:

-   **Sistema de movimentação:** O robô passou por diversas evoluções de movimento ao longo do projeto. Iniciou com um espelhamento de movimentação simples (mirror), passou por modos de movimentação aleatória e orbital, até chegar a um sistema híbrido com **Wave Surfing** (evasão baseada em ondas de tiros inimigos) e predição por **ARIMA** (modelo matemático de séries temporais). Foram também implementados mecanismos de segurança como a inversão de direção em caso de colisão com paredes (_Wall Hump Fix_) e a adaptação de comportamento em combate _melee_ (múltiplos inimigos) versus duelos 1v1.
    
-   **Sistema de mira (Virtual Guns):** Foi desenvolvido um arsenal de 9 armas virtuais executadas em paralelo. Cada arma utiliza um algoritmo diferente de predição — entre eles ARMA, ARIMA, Rede Neural, KNN Pesado, Guess Factor e Linear — e o robô registra estatisticamente qual delas acerta mais contra cada oponente, selecionando automaticamente a de maior eficácia em tempo real. Esse sistema é conhecido na comunidade Robocode como _Virtual Guns_.
    
-   **Sistema de radar:** Foi implementado um mecanismo de _radar lock_ (travamento de radar), que mantém o sensor continuamente focado no inimigo-alvo, garantindo atualização constante dos dados de telemetria necessários para os algoritmos de mira e evasão.
    
-   **Classificação dinâmica de adversários:** O BT_7274 analisa o comportamento de cada inimigo durante a batalha e o classifica em um dos quatro perfis: _Clinger_ (colisor), _Surfer_ (esquiva avançada), _Intermediário_ (oscilatório) ou _Básico_ (linear/circular). Com base nessa classificação, o robô ajusta automaticamente seus motores de movimento e de mira para o perfil mais eficaz contra aquele oponente específico.
    
-   **Persistência de memória entre rounds:** Através de estruturas estáticas (`static HashMap`), o robô retém, entre os rounds de uma mesma partida, o histórico de qual arma virtual foi mais eficaz contra cada adversário e qual foi sua classificação comportamental, evitando reaprender do zero a cada batalha.
    

#### 3.2 Utilização do Git para Controle de Versão

O Git foi utilizado como ferramenta de controle de versão ao longo de todo o projeto, e sua presença foi determinante para que o grupo conseguisse trabalhar de forma paralela sem conflitos destrutivos. Cada nova funcionalidade ou correção foi registrada como um commit individual, permitindo rastrear com precisão a evolução do código. _(A estrutura de branches, commits e pull requests adotada pelo grupo é detalhada na Seção 4.)_

O que o histórico de commits evidencia na prática é uma progressão técnica clara dividida em três fases:

-   **Fase inicial (28–29 de abril):** criação da estrutura do repositório, definição visual do robô e início do desenvolvimento paralelo com branches individuais.
-   **Fase de evolução (5–12 de maio):** ciclos intensos de implementação onde múltiplos membros contribuíam simultaneamente, com integrações via pull requests — cobrindo movimentação, sistemas de mira, ARMA, ARIMA, Guess Factor e Anti-Clinger.
-   **Fase de refinamento (15 de maio):** Maurício conduziu uma sequência final de commits implementando as funcionalidades mais avançadas — KD-Tree híbrida, Virtual Guns, Wave Surfing e correções de radar lock — consolidando a versão final do robô.

#### 3.3 Colaboração entre os Membros e Uso do GitHub

O projeto foi desenvolvido por quatro integrantes — Felipe Rezende, Gustavo Niehues, Lucas de Godoy Chicarelli e Mauricio Telles Silva — com colaboração mediada pelo GitHub.

O fluxo de trabalho adotado pelo grupo foi baseado em branches e pull requests. Cada membro trabalhava em sua própria branch, e as integrações ao código principal eram feitas por meio de pull requests, que foram revisados antes de serem mesclados. Ao longo do projeto, foram abertos e integrados **7 pull requests**, cobrindo desde alterações de movimentação até implementações mais complexas como ARIMA e Virtual Guns.

No que diz respeito à divisão de responsabilidades, **Mauricio Telles Silva** atuou como principal desenvolvedor do BT_7274, sendo responsável pela maior parte da implementação dos sistemas que definem o comportamento e a inteligência do robô. Entre suas contribuições estão os modelos preditivos ARMA e ARIMA, o sistema de Virtual Guns com múltiplas armas executadas em paralelo, a KD-Tree e sua variante híbrida para busca de vizinhos, o Guess Factor Targeting, o Wave Surfing para evasão avançada, os mecanismos Anti-Clinger, a votação de tracking, a movimentação caótica adaptativa, o radar lock e diversas correções e otimizações que contribuíram para a estabilidade e competitividade do robô. Sua participação esteve presente durante todas as etapas do desenvolvimento, desde a criação da base do projeto até os refinamentos finais utilizados na competição.

**Lucas de Godoy Chicarelli** contribuiu para a evolução do projeto por meio da implementação e integração de funcionalidades complementares, além de atuar de forma significativa na documentação e organização do repositório. Também participou da estruturação dos materiais de apoio ao desenvolvimento, da manutenção do README e da configuração do ambiente de trabalho, auxiliando na consolidação das diferentes versões do robô ao longo do semestre.

**Felipe Rezende** colaborou com a manutenção e organização do projeto, realizando ajustes estruturais, correções pontuais, limpeza do repositório e suporte ao processo de integração das funcionalidades desenvolvidas pela equipe. Sua atuação contribuiu para manter o código organizado e adequado para distribuição e execução dentro do ambiente do Robocode.

**Gustavo Niehues** participou das etapas finais do desenvolvimento, realizando integrações e ajustes experimentais no robô, além de colaborar com testes e validações das versões mais recentes. Sua participação auxiliou no refinamento da versão utilizada durante a competição.

Além das contribuições diretamente incorporadas ao BT_7274, os integrantes também realizaram experimentos, testes de estratégias e desenvolvimento de robôs alternativos ao longo do projeto. Essas atividades serviram como ambiente de validação para diferentes abordagens de movimentação, evasão e mira, contribuindo para a tomada de decisões que orientaram a evolução do robô principal.

O desenvolvimento do BT_7274 foi resultado de um processo colaborativo em que diferentes contribuições foram integradas ao longo do semestre. Entretanto, a implementação do núcleo técnico e dos principais algoritmos responsáveis pelo desempenho competitivo do robô ficou concentrada em Mauricio Telles Silva, enquanto os demais integrantes contribuíram com suporte ao desenvolvimento, documentação, organização do projeto, testes e funcionalidades complementares.


### 4. Estrutura do Git utilizada

O desenvolvimento do projeto utilizou Git e GitHub como ferramentas de controle de versão e integração de código. O fluxo de trabalho foi baseado em múltiplas branches e em um fork do repositório, permitindo que diferentes funcionalidades fossem desenvolvidas e testadas de forma independente antes da integração ao código principal. O repositório utilizou as branches **master** e **main** como bases de desenvolvimento e integração, além das branches individuais **lucas**, utilizada por Lucas de Godoy Chicarelli, **felipe-robozika** e **feliperezn-patch-1**, utilizadas por Felipe Rezende, e **GustavoNiehues**, utilizada por Gustavo Niehues. Paralelamente, também foi utilizado um fork do projeto para o desenvolvimento de funcionalidades e experimentos realizados de forma isolada do repositório principal. A integração das alterações ocorreu por meio de commits, merges e pull requests, permitindo o desenvolvimento simultâneo de diferentes módulos do BT_7274, reduzindo conflitos e garantindo o rastreamento completo da evolução do código ao longo do projeto.


### 5. Resultados e Aprendizados
### 6. Conclusão
### 7. Anexos
