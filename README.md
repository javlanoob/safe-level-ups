# Safe Level Ups

Shows the real level up box without the game interrupting you.

Levelling up normally freezes your character for a moment while the server opens the level up
interface. On a hardcore ironman that pause happens whether or not it is a good time for it.

Turn off *Level-up pop-up notifications* in the in-game settings (Settings > Interfaces) and the
game stops sending it. This plugin then opens the very same interface itself, from the client, so
nothing is waiting on the server and nothing stalls.

It is the game's own interface, not a recreation: the same models, fonts, layout and wording.

Click anywhere or press space to close it. The click still goes through to whatever you clicked on.
Because nothing is stalling you, a click you had already lined up for something else would otherwise
dismiss the box before you had read it, so it stays up for a moment first and the click takes effect
once that moment has passed.

A box waits for the chatbox to be free, so it never lands on top of a dialogue you are reading, an
"Enter amount:" prompt or a bank search.

Every level works, 99 included. The one thing it does not copy is the extra skillcape pop-up that
follows a 99, which names the shop selling your cape - that wording is written per skill and only
the server has it, so it is left to the game rather than guessed at.
