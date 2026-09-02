# Hostile raw HTML

<script>window.markflowHostile = true;</script>

<img src="image.png" onerror="alert('active content')">

<div style="background-image: url(javascript:alert(1))">active style payload</div>

<a href="javascript:alert('navigation')">hostile navigation</a>
