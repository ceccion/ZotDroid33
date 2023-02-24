ZotDroid
========

A Zotero Client for Android devices, Updated for Android SDK 33
-----------------------------------
This is my attempt to resurrect the ZotDroid open source Zotero Android App, because I want to sync to my phone and I don't want to pay for Zotero's server storage.
Naturally, this means the best option is to spend several hours of my free time in Android Development Hell.

The app is now in a state I call "Good Enough". It has all the important features of the 2017 release, plus a few more that I needed to add because of changes I made to get the thing working with the new SDK. There's still more I want to do to the app when I have the time, but I'm pretty busy, so don't hold your breath (or better yet, if you have an idea, implement it and submit a pull request!)

Current Version
---------------

0.5

Working Features
----------------

* Syncing with WebDav
* Downloading, opening, and deleting files

Features I Still Need to Test
-----------------------------

* Syncing with the Zotero servers
* Write functions, namely writing new notes and new tags

Future Features
---------------

* Better UI, specifically removing the "ZotDroid 33" from the top of every page and adding a nicer collection selector
* Examine whether the client key and client secret should be randomly generated on first startup
* Add support for annotations

Acknowledgements (Inherited from the original repo)
---------------------------------------------------

* ZotDroid makes use of [Signpost](https://github.com/mttkay/signpost). This lives in the external directory for these who wish to build ZotDroid themselves.
* [XListView-Android](https://github.com/Maxwin-z/XListView-Android) - For the very handy list dragging animations and event handling.

Licence
-------

    ZotDroid33 - An Android client for Zotero
    Copyright (C) 2023  Nick Ceccio

    This program is licensed under version 3 of the GNU Affero General Public License

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.


