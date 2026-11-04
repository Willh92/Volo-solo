/*
 * Solo - A small and beautiful blogging system written in Java.
 * Copyright (c) 2010-present, b3log.org
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

/**
 * @file frontend tool.
 *
 * @author <a href="http://vanessa.b3log.org">Liyuan Li</a>
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 */

'use strict'
const gulp = require('gulp')
const concat = require('gulp-concat')
const terser = require('gulp-terser')
const sass = require('gulp-sass')(require('sass'));
const rename = require('gulp-rename')
let autoprefixer
let deleteAsync

async function loadTools () {
  autoprefixer = (await import('gulp-autoprefixer')).default
  deleteAsync = (await import('del')).deleteAsync
}

function sassSkinProcess () {
  return gulp.src('./src/main/webapp/skins/*/css/*.scss').
    pipe(sass({
      style: 'compressed',
      loadPaths: ['node_modules'],
    })).
    pipe(autoprefixer({
      cascade: false,
    })).
    pipe(gulp.dest('./src/main/webapp/skins/'))
}

function sassWatch () {
  gulp.watch(['./src/main/webapp/skins/*/css/*.scss'], sassSkinProcess)
  gulp.watch(['./src/main/webapp/scss/*.scss'], sassCommonProcess)
}

function sassCommonProcess () {
  return gulp.src('./src/main/webapp/scss/*.scss').
    pipe(sass({
      style: 'compressed',
      loadPaths: ['node_modules'],
    })).
    pipe(autoprefixer({
      cascade: false,
    })).
    pipe(gulp.dest('./src/main/webapp/scss/'))
}

gulp.task('watch', gulp.series(loadTools, vendorAssets, sassWatch))

async function vendorAssets () {
  const fs = require('node:fs/promises')
  const path = require('node:path')
  const webapp = './src/main/webapp'
  // Vditor appends /dist/... to its cdn option, so preserve the package layout.
  const vditorDirectory = path.resolve(webapp, 'js/lib/vditor')
  const expectedVditorDirectory = path.resolve(__dirname, 'src/main/webapp/js/lib/vditor')
  if (vditorDirectory !== expectedVditorDirectory) throw new Error('Unexpected generated Vditor directory')
  await fs.rm(vditorDirectory, { recursive: true, force: true })
  const copies = [
    ['node_modules/vditor/dist', webapp + '/js/lib/vditor/dist'],
    ['node_modules/font-awesome/css', webapp + '/js/lib/font-awesome/css'],
    ['node_modules/font-awesome/fonts', webapp + '/js/lib/font-awesome/fonts'],
    ['node_modules/@fortawesome/fontawesome-free/css', webapp + '/js/lib/fontawesome/css'],
    ['node_modules/@fortawesome/fontawesome-free/webfonts', webapp + '/js/lib/fontawesome/webfonts'],
    ['node_modules/jquery/dist/jquery.min.js', webapp + '/js/lib/jquery/jquery.min.js'],
    ['node_modules/justifiedGallery/dist', webapp + '/js/lib/justifiedGallery'],
    ['node_modules/@fancyapps/fancybox/dist', webapp + '/js/lib/fancybox'],
    ['node_modules/vditor/LICENSE', webapp + '/js/lib/vditor/LICENSE'],
    ['node_modules/jquery/LICENSE.txt', webapp + '/js/lib/jquery/LICENSE.txt'],
    ['node_modules/@fortawesome/fontawesome-free/LICENSE.txt', webapp + '/js/lib/fontawesome/LICENSE.txt'],
    ['node_modules/justifiedGallery/LICENSE', webapp + '/js/lib/justifiedGallery/LICENSE'],
    ['node_modules/@fontsource/roboto/files/roboto-latin-500-normal.woff2', webapp + '/js/fonts/roboto/Roboto-Medium.woff2'],
    ['node_modules/@fontsource/roboto/files/roboto-latin-500-normal.woff', webapp + '/js/fonts/roboto/Roboto-Medium.woff'],
    ['node_modules/@fontsource/roboto/LICENSE', webapp + '/js/fonts/roboto/LICENSE.txt'],
  ]
  for (const [source, destination] of copies) {
    await fs.mkdir(require('node:path').dirname(destination), { recursive: true })
    await fs.cp(source, destination, { recursive: true })
  }
}

function minJS () {
  // minify js
  return gulp.src(['./src/main/webapp/js/*.js', '!./src/main/webapp/js/*.min.js']).
    pipe(rename({ suffix: '.min' })).
    pipe(terser({
      output: {
        ascii_only: true,
      },
    })).
    pipe(gulp.dest('./src/main/webapp/js/'))
}

function miniAdmin () {
  // concat js
  const jsJqueryUpload = [
    './src/main/webapp/js/admin/admin.js',
    './src/main/webapp/js/admin/pagination.js',
    './src/main/webapp/js/admin/logs.js',
    './src/main/webapp/js/admin/editor.js',
    './src/main/webapp/js/admin/tagPicker.js',
    './src/main/webapp/js/admin/tablePaginate.js',
    './src/main/webapp/js/admin/article.js',
    './src/main/webapp/js/admin/comment.js',
    './src/main/webapp/js/admin/articleList.js',
    './src/main/webapp/js/admin/draftList.js',
    './src/main/webapp/js/admin/pageList.js',
    './src/main/webapp/js/admin/others.js',
    './src/main/webapp/js/admin/linkList.js',
    './src/main/webapp/js/admin/followList.js',
    './src/main/webapp/js/admin/preference.js',
    './src/main/webapp/js/admin/githubRepositories.js',
    './src/main/webapp/js/admin/security.js',
    './src/main/webapp/js/admin/themeList.js',
    './src/main/webapp/js/admin/pluginList.js',
    './src/main/webapp/js/admin/userList.js',
    './src/main/webapp/js/admin/categoryList.js',
    './src/main/webapp/js/admin/commentList.js',
    './src/main/webapp/js/admin/plugin.js',
    './src/main/webapp/js/admin/main.js',
    './src/main/webapp/js/admin/about.js',
    './src/main/webapp/js/admin/usite.js']
  return gulp.src(jsJqueryUpload).
    pipe(terser({
      output: {
        ascii_only: true,
      },
    })).
    pipe(concat('admin.min.js')).
    pipe(gulp.dest('./src/main/webapp/js/admin'))

}

function miniAdminLibs () {
  // concat js
  const jsJqueryUpload = [
    './src/main/webapp/js/lib/jquery/jquery.min.js',
    './src/main/webapp/js/lib/jquery/jquery.bowknot.min.js',
    './src/main/webapp/js/lib/jquery/jquery.showtips.js',
    './src/main/webapp/js/lib/jquery/jquery.cookie.min.js',
    './src/main/webapp/js/lib/jquery.qrcode.min.js']
  return gulp.src(jsJqueryUpload).
    pipe(terser({
      output: {
        ascii_only: true,
      },
    })).
    // https://github.com/b3log/solo/issues/12522
    pipe(concat('admin-lib.min.js')).
    pipe(gulp.dest('./src/main/webapp/js/lib/compress/'))

}

function miniPjax () {
  // concat js
  const jsPjax = [
    './src/main/webapp/js/lib/jquery/jquery.min.js',
    './src/main/webapp/js/lib/jquery/jquery.pjax.js',
    './src/main/webapp/js/lib/nprogress/nprogress.js']
  return gulp.src(jsPjax).
    pipe(terser({
      output: {
        ascii_only: true,
      },
    })).
    pipe(concat('pjax.min.js')).
    pipe(gulp.dest('./src/main/webapp/js/lib/compress/'))
}

function minSkinJS () {
  // minify js
  return gulp.src('./src/main/webapp/skins/*/js/*.js').
    pipe(rename({ suffix: '.min' })).
    pipe(terser({
      output: {
        ascii_only: true,
      },
    })).
    pipe(gulp.dest('./src/main/webapp/skins/'))
}

function cleanProcess () {
  return deleteAsync([
    './src/main/webapp/js/*.min.js',
    './src/main/webapp/js/admin/admin.min.js',
    './src/main/webapp/js/lib/compress/*.min.js',
    './src/main/webapp/skins/*/js/*.min.js',
    './src/main/webapp/**/*.gz'])
}

async function compressStaticAssets () {
  const fs = require('node:fs/promises')
  const path = require('node:path')
  const zlib = require('node:zlib')
  const roots = ['js', 'scss', 'skins', 'plugins', 'images'].map(dir => path.resolve(__dirname, 'src/main/webapp', dir))
  const compressible = /\.(?:js|css|svg|json)$/i
  async function walk (dir) {
    let entries
    try {
      entries = await fs.readdir(dir, { withFileTypes: true })
    } catch {
      return
    }
    for (const entry of entries) {
      const fullPath = path.join(dir, entry.name)
      if (entry.isDirectory()) {
        await walk(fullPath)
      } else if (entry.isFile() && compressible.test(entry.name)) {
        const raw = await fs.readFile(fullPath)
        if (raw.length < 1024) continue
        const gz = zlib.gzipSync(raw, { level: 9 })
        if (gz.length < raw.length) {
          await fs.writeFile(fullPath + '.gz', gz)
        }
      }
    }
  }
  for (const root of roots) {
    await walk(root)
  }
}

gulp.task('default',
  gulp.series(loadTools, cleanProcess, vendorAssets, sassSkinProcess, sassCommonProcess,
    gulp.parallel(minSkinJS, minJS),
    gulp.parallel(miniPjax, miniAdmin, miniAdminLibs), compressStaticAssets))
