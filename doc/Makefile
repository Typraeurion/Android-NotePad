# Simple makefile for the documentation

.PHONY: all pdf html clean

all: pdf html

pdf: Building_NotePad.pdf NotePad.pdf

html: Building_NotePad/Building_NotePad.html NotePad/NotePad.html

Building_NotePad.pdf: Building_NotePad.texinfo
	texi2pdf Building_NotePad.texinfo

Building_NotePad:
	mkdir -p Building_NotePad

Building_NotePad/Building_NotePad.html: Building_NotePad.texinfo | Building_NotePad
	texi2html --split node Building_NotePad.texinfo
	touch $@

NotePad.pdf: NotePad.texinfo
	texi2pdf NotePad.texinfo

NotePad:
	mkdir -p NotePad

NotePad/NotePad.html: NotePad.texinfo | NotePad
	texi2html --split node NotePad.texinfo
	touch $@
