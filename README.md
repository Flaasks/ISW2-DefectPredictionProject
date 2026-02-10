Ingegneria del Software 2 Project-1 - Univerity of Tor Vergata

This project presents a comprehensive analysis of defect prediction in software
systems using ML classifiers. I have conducted a what-if analysis on two Apache
projects (BOOKKEEPER and SYNCOPE) to evaluate the potential impact of
refactoring actionable features on bug prevention. My methodology involved dataset
generation from Git and JIRA histories, feature extraction at method-level gran-
ularity, classifier training and evaluation, and simulation of refactoring scenarios.
The results (see: Report_ISW2_DiPalmaFlavio.pdf) demonstrate that reducing actionable features can lead to a reduction
in predicted defects, with drop rates of 18.16% for BOOKKEEPER and 21.42%
for SYNCOPE. The codebase maintains zero code smells on SonarCloud, ensuring
high code quality
