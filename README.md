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

## 🌟 Principais Funcionalidades e Diferenciais

* **Override de Surfer Adaptativo:** Alterna dinamicamente a sua tática de esquiva e mira de acordo com a precisão e o perfil do inimigo, escolhendo entre sistemas como *KNN Pesado*, *Anti-Trem* e *GunWave GuessFactor*.
* **Override MRM Agressivo:** Em combates 1v1 contra robôs que não possuem esquiva complexa (*non-surfers*), ele desativa a defesa reativa de ondas e assume uma órbita agressiva a 300px de distância para maximizar o dano.
* **Shadow Waves & Shadow Ativo:** Mecânica inovadora que projeta as intersecções dos próprios projéteis no ar para se camuflar e se esconder atrás de seus próprios tiros.
* **Multi-Wave Surfing Pesado:** Ativado automaticamente se houver mais de 3 inimigos vivos na arena, triplicando a atração de sombras e gerenciando múltiplas ondas de perigo simultaneamente.
* **Persistência Estática:** Mantém um histórico detalhado e o perfil classificado de cada adversário (*Básico*, *Intermediário*, *Surfer* ou *Clinger*) salvo de um round para o outro.
* **HUD Visual Avançado (`onPaint`):** Renderiza em tempo real linhas de laser contínuas, simulações de trajetória de alvo, círculos de expansão de ondas e um painel completo com a taxa de acerto de cada uma das suas **9 Virtual Guns**.

---

## 🛠️ Sistema de Armas Virtuais (Virtual Guns)
O robô monitora a eficácia de 9 sub-mecanismos de mira independentes para travar na arma de maior precisão contra cada inimigo específico:
1. `Auxiliar`
2. `ARMA` (Analítica / Preditiva)
3. `ARIMA`
4. `Rede Neural`
5. `Dynamic Clustering`
6. `Anti-Trem`
7. `Média GF`
8. `KNN Pesado`
9. `GunWave GF` (Com capacidade elevada para 101 BINS de resolução)

---

## 📜 Estrutura de Código e Lista de Funções

Abaixo estão listadas todas as funções e métodos organizados por classe e escopo de atuação no código-fonte.

### 1. Classe Principal (`BT_7274`)

Gere os estados globais do robô, as respostas do sistema físico aos eventos do Robocode, o HUD gráfico e as rotinas de decisão estratégica.

* `BT_7274()`: **Construtor.** Inicializa o motor de movimento secundário e aloca as coleções estruturadas para os pontos de simulação espacial.
* `run()`: *[Herdado de AdvancedRobot]* Lógica de execução principal e contínua do ciclo de vida do robô. Define as configurações de cores da carcaça/radar, desconecta o giro do radar/canhão da base e roda o loop infinito de iteração de turnos.
* `onScannedRobot(ScannedRobotEvent e)`: *[Herdado de AdvancedRobot]* Evento disparado continuamente quando o radar detecta um robô inimigo. É o núcleo analítico: atualiza dados telemétricos, registra histórico de velocidades, escolhe a melhor arma do arsenal de Virtual Guns e gerencia as ordens de disparo e travamento de radar.
* `onPaint(Graphics2D g)`: *[Herdado de AdvancedRobot]* Renderiza toda a interface gráfica de telemetria na tela de simulação. Desenha o status atual do robô, taxas de acerto globais e individuais, predição de posições futuras do adversário através de pontos amarelos e o mapeamento de risco das ondas.
* `onHitWall(HitWallEvent e)`: *[Herdado de AdvancedRobot]* Trata colisões físicas contra as paredes. Inverte o sentido de movimento, força um deslocamento reverso para escapar do travamento mecânico (`Wall Hump Fix`) e reposiciona o alvo de fuga temporariamente no centro do campo de batalha.
* `onHitRobot(HitRobotEvent e)`: *[Herdado de AdvancedRobot]* Evento acionado quando ocorre colisão direta com outro robô. Ativa instantaneamente o flag de detecção de inimigos do tipo `Clinger` (grudadores) para forçar um distanciamento defensivo imediato.
* `executarSurfing()`: Função central da defesa do robô. Avalia todas as ondas de tiros inimigos em aproximação, calcula os tempos de voo dos projéteis adversários e decide o melhor vetor de esquiva. Possui salvaguardas para desativação inteligente em cenários específicos de 1v1.
* `atualizarCaminhoVisual(OndaInimiga ondaPrimaria, int direcaoAcao)`: Atualiza a lista interna de coordenadas cartesianas do caminho ideal para que o sistema de pintura gráfica exiba a linha contínua de evasão preditiva na tela.
* `preverRiscoMovimento(OndaInimiga ondaPrimaria, int direcaoAcao)`: Realiza uma simulação hipotética "passo a passo" do robô movendo-se para frente, para trás ou permanecendo estático. Calcula matematicamente o risco cumulativo de cada escolha com base nos bins de GuessFactor mais atingidos pelo oponente.

### 2. Classe Interna Estática `Utilitario`

Agrupa funções matemáticas puras e algoritmos geométricos compartilhados por todo o sistema.

* `limitar(double valor, double min, double max)`: Clampa um valor numérico para que ele não ultrapasse as barreiras informadas. Essencial para evitar que o robô tente se mover para fora dos limites físicos da arena.
* `aleatorioEntre(double min, double max)`: Retorna um número randômico decimal contido estritamente dentro do intervalo fornecido.
* `projetar(Point2D origem, double angulo, double distancia)`: Executa cálculos trigonométricos baseados em seno e cosseno para projetar e retornar uma nova coordenada (`Point2D.Double`) no plano cartesiano a partir de um ponto, uma direção radial e uma distância.
* `anguloAbsoluto(Point2D origem, Point2D alvo)`: Retorna o arco tangente real (ângulo absoluto em radianos) formado pela linha reta entre dois pontos no campo de batalha.
* `sinal(double v)`: Retorna `1` se o valor de entrada for positivo ou zero, e `-1` se o valor for negativo. Utilizado para simplificar multiplicações de orientação direcional.

### 3. Classe Interna `OndaInimiga`

Representa a simulação matemática de um projétil disparado por um adversário se propagando pelo cenário como uma onda circular.

* `checarAcerto(double posX, double posY, long tempoAtual)`: Determina se o raio de expansão da onda de energia do tiro inimigo ultrapassou a posição atual do nosso robô. Em caso positivo, computa o bin correspondente no histórico estatístico e limpa a onda da fila de processamento.
* `obterBin(double alvoX, double alvoY)`: Calcula o ângulo de deslocamento relativo do robô em relação à trajetória retilínea original do tiro inimigo e mapeia esse desvio para um índice numérico (Bin de 0 a 46) proporcional ao Ângulo de Escape Máximo.

### 4. Classe Interna `Movimento_1VS1`

Gerenciador secundário de movimentação geométrica utilizado como fallback contra robôs mais básicos ou em situações controladas de duelo individual.

* `Movimento_1VS1(AdvancedRobot _robô)`: Construtor. Vincula a instância principal para controle dos eixos de direção.
* `onScannedRobot(ScannedRobotEvent e)`: Implementa um algoritmo orbital tático clássico. Calcula uma trajetória circular ao redor do inimigo que se ajusta em relação às paredes do mapa para evitar encurralamentos.

### 5. Classe Interna `Onda`

Estende a classe `Condition` do Robocode. Rastreia as ondas geradas pelos **nossos próprios disparos** para pontuar a assertividade de cada uma das 9 Virtual Guns.

* `Onda(AdvancedRobot _robô)`: Construtor que inicializa as propriedades da onda do disparo do BT-7274, salvando o ponto de partida do canhão e a assinatura de comportamento do inimigo naquele instante.
* `test()`: Método avaliador assíncrono disparado automaticamente pelo Robocode a cada turno. Monitora a expansão do tiro até o alvo. Quando a onda intercepta o oponente, ele calcula qual bin GuessFactor seria o ideal, pontua com pesos atenuados as armas virtuais que previram aquele bin com sucesso e limpa o evento customizado da memória.
* `registrarMiraKNNPesado(Robo inimigo, Robo meuRobo)`: Extrai as características de estado atuais (*features*) do inimigo, correlaciona com o bin real atingido pela onda e insere no banco de dados multidimensional do algoritmo KNN para consultas de mira futuras.

---

## 🏆 Resultados da Batalha

* **Batalha Melee**: 🥇 1º Lugar (11 robôs)
* **Batalha 1x1**: 🥈 2º Lugar (3 robôs)
