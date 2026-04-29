# zaomeng.skill

[中文](README.md) | [English](README.en.md)

`zaomeng` is not a generic chatbot.

It is closer to a skill that brings fictional characters back onto the stage:

- distill character profiles from a novel
- extract relationships and generate relationship graphs
- let characters speak according to their personality, stance, and bonds

If what you want is not "some pleasant AI chat" but "make Lin Daiyu sound like Lin Daiyu" or "let a whole cast argue in character inside one scene," that is exactly the problem this project is trying to solve.

## What You Can Do With It

### 1. Distill characters

Give it a novel, and it will try to build reusable character profiles from the original text, including:

- personality tone
- core motivation
- speaking style
- decision logic
- relationship tendency
- emotional triggers
- character arc

The goal is not a one-page summary. The goal is a profile that can actually support later roleplay and dialogue.

### 2. Generate relationship graphs

It does not stop at relation fields. It also exports a visual relationship graph so you can quickly see who trusts whom, who depends on whom, and where tension lives.

That becomes especially useful for ensemble fiction, fanwork, character analysis, and interactive story setups.

### 3. Enter in-character dialogue

After distillation, you can keep going in two main modes:

- `act`: you play one character, other characters reply in character
- `observe`: you stay out of the scene and let several characters interact naturally

That goes beyond "give me a reply in this character's tone" because it also uses persona files, relationship state, memory, and the active scene.

## How It Works Now

The current version is **LLM-first**:

- the host or runtime LLM does the actual language generation
- `zaomeng` prepares prompts, constraints, persona files, relationship context, and helper outputs
- the skill path prefers reusing the host model that already exists

So the emphasis is no longer "hardcode a large pile of rules and glue lines together." The emphasis is giving the model clearer persona and relationship constraints so the output sounds more alive and more in character.

## Best Fit

It is especially useful for:

- novel roleplay
- multi-character group chat
- fanwork assistance
- character analysis
- long-form cast organization
- worldview and persona consistency checks

If your recurring complaint is "the AI talks, but it does not sound like this person," this project is aimed right at that gap.

## Recommended First-Time Flow

Most users do not need to start with commands.

The right order is simple:

1. provide the novel text
2. say which characters you want distilled
3. wait for persona files and the relationship graph
4. enter `act` or `observe`

### Natural-language requests that work well

```text
Distill Lin Daiyu, Jia Baoyu, and Xue Baochai from this novel
```

```text
After distillation, put Lin Daiyu, Jia Baoyu, and Xue Baochai into group chat mode
```

```text
Let me play Jia Baoyu. Lin Daiyu should answer me
```

```text
Generate the relationship graph. I want the HTML version
```

## High-Quality Dialogue Examples

### Example 1: `act` mode

```text
User      ❯  Let me play Jia Baoyu and chat with Lin Daiyu.
            Set the scene at Qinfang Pavilion in the Grand View Garden,
            on a spring afternoon, with Daiyu standing by the bridge watching fish.

zaomeng   ❯  (On a spring afternoon, sunlight filters through willow branches
            and falls across the waters of Qinfang Stream.
            Daiyu stands alone by the bridge, one hand resting lightly
            against the rail as she watches the koi drifting below.
            The wind lifts the edge of her skirt and a few loose strands
            by her temple. She seems lost in thought.
            In her hand is a fallen flower she must have picked up
            without thinking, her fingers holding it a little too tightly.)

User      ❯  Cousin Lin, in weather this warm, why are you standing here alone
            and drifting into thought?
            If the wind keeps up, you will grow faint if you stay too long.

zaomeng   ❯  (At the sound of his voice, she does not turn at once.
            Her eyes remain fixed on the glittering water and the fish below.
            After a while she answers softly.)
            You do concern yourself with too much.
            I am only watching fish, and the fish do not seem to mind me there.
            That is more than can be said for lively places full of people,
            where one more person is always in the way.
            (Her fingers unconsciously rub at the fallen flower.
            A few petals break loose and drift onto the water,
            only to be pecked away by the fish.
            Then she turns her face slightly, glancing at Baoyu from the corner of her eye,
            her tone still cool.)
            If anyone is likely to grow faint, it is probably you.
            On such a fine day, why are you not off keeping Baochai company over tea and chess?
            Yet here you are, coming to this quiet place to look for me.
            Are you not afraid someone will say you still have no proper sense of yourself?

User      ❯  How did such a fine afternoon turn into words so full of jealousy?
            For me, no lively place in the world is worth half the charm
            of Qinfang Bridge when you are standing here.

zaomeng   ❯  (The flower in her hand trembles.
            Her fingers tighten slightly around it.
            A faint flush slowly reaches the tips of her ears,
            yet she turns her face aside so he cannot see it clearly.)
            ...Hmph.
            (There is a brief silence. When she speaks again,
            her voice is a little softer than before,
            as though the spring breeze had worn down one sharp edge.)
            You have said such things more than once.
            However pleasant they sound, too many repetitions make them less rare.
            (A koi flicks its tail, splashing a few drops of water onto the back of her hand.
            She lowers her eyes to look at it, and suddenly murmurs:)
            "Soft the east wind beyond the peach-blossom curtain;
            within it, the morning toilette lies still undone..."
            (The moment the words leave her mouth she seems to realize she has betrayed herself.
            The flush deepens. She lifts the broken flower as though to hide the corner of her mouth,
            her gaze sliding away into the distance.)
            ...I was only watching fish. I was not reciting poetry for your sake.
            If you find it dull here, then go elsewhere.
            You need not stand here and offend my eyes.
```

### Example 2: `observe` mode

```text
User      ❯  Put Jia Baoyu, Lin Daiyu, and Xue Baochai into group chat mode
            with no scene specified.

zaomeng   ❯  All character profiles and relationship data have been loaded.
            Setting the scene...
            Group chat mode started.
            Scene: Hengwu Court, autumn afternoon.
            Baochai is inside sorting colored thread.
            Baoyu comes to borrow a book and finds Daiyu already there.
            The three gather in Baochai's quarters.
            Timeline: after Chapter 42.
            (The Golden Orchid vow has passed.
            Daiyu and Baochai appear reconciled on the surface,
            but the undercurrent of the triangle remains.)

Baoyu: How fortunate that both my dear sisters are here.
       I only came to borrow a book from Baochai,
       yet Lin Daiyu is here too, and that saves me another walk.
       The weather is so fine today. Why don't we all sit together and talk for a while?

Baochai: You have arrived at just the right time.
         I do happen to have a newly acquired copy of Guangyun here.
         If you want to borrow it, take it.
         Only do not leave it lying in Yihong Courtyard to gather dust again.
         As for Cousin Lin, she came to see me.
         Sit down. I shall have Ying'er bring tea.

Daiyu: Then I came at the wrong time.
       Had I known you were here, I would not have come,
       lest someone say again that I have come only to disturb your private talk.

zaomeng   ❯  That was the first round of group chat.
            You can now continue the topic, switch the scene,
            cue a specific character, or enter the next round directly.
```

## Installation

### If you just want to use it

Install it as a skill first.

#### OpenClaw

```bash
openclaw skills install wkbin/zaomeng-skill
```

#### ClawHub

```bash
npx clawhub@latest install zaomeng-skill
```

or:

```bash
pnpm dlx clawhub@latest install zaomeng-skill
```

```bash
bunx clawhub@latest install zaomeng-skill
```

This is now the main path: **prompt-first skill + host LLM**.

### If you are developing on it

Clone the repository directly:

```bash
git clone https://github.com/wkbin/zaomeng.git
cd zaomeng
```

The repository still keeps the source code, tests, and CLI needed for local development and debugging, but that is no longer the main entry point for ordinary users.

## Typical Outputs

After a run, you will usually get:

- character profiles
- relationship results
- relationship graph HTML
- in-character dialogue output
- persistent correction memory

Character storage is now markdown-first rather than legacy-JSON-first.

## What Changed From Earlier Versions

If you read an older introduction before, these points have changed:

- it is now **LLM-first**, not rule-template-first
- the skill path reuses the host model first instead of assuming separate runtime configuration
- `clawhub-zaomeng-skill` now centers on `prompts/`, `references/`, and helper scripts
- outputs now emphasize markdown persona files, relationship graphs, and reusable chat artifacts

## Project Layout

If you are only here to use it, you can skip this section.

The repository is now roughly split into three layers:

- `src/`: core source code
- `clawhub-zaomeng-skill/`: publishable skill package
- `tests/`: regression coverage

The most important assets in the skill package are:

- `prompts/`
- `references/`
- `tools/prepare_novel_excerpt.py`
- `tools/build_prompt_payload.py`
- `tools/export_relation_graph.py`

## In One Line

`zaomeng` is not trying to be "an AI that can talk."

It is trying to let people from novels speak again with their own personality, relationships, and voice.

## License

Main project: `AGPL-3.0-only`

`clawhub-zaomeng-skill`: `MIT-0`
