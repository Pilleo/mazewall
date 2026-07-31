How to turn "slow" AI models into effective electronic juniors.

Lately, using "warm" or "cold" tokens is proving much cheaper than constantly running fast models. Take, for example, the agent Jules from Google. It works quite slowly, but in terms of token economy,
it's much more affordable than lightning-fast alternatives.

However, the current experience of interacting with such systems leaves much to be desired due to high latency. The main goal is to change the approach to working with such models so that they
resemble interacting with a leisurely, but quite capable junior. You give the task, the model thinks in the background, and then sends a list of clarifying questions, which you answer.

The most important part of the proposed system is a special scheduler that takes strictly defined tasks. They indicate which files are supposed to be changed, which helps to avoid conflicts. If a
collision still arises, the script can automatically assign another task to Jules to resolve them.

Additionally, for large projects, this instrument can automatically run reviews on individual modules, suggesting improvements when the number of tasks decreases, and then sends a notification to the
person to make the final decision.
