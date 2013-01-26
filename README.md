SimpleGit
=========

A simple Jenkins Git plugin designed to be used only by being triggered by Git Update Hooks.

You can pass in the NEWREV and OLDREV provided in Git hooks as the "Revision Range Start" and "Revision Range End" respectively, and that range will be built and included in the change set. 
