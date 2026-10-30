// assets/charts.js
(function () {
  var style = getComputedStyle(document.documentElement);
  var p0 = style.getPropertyValue('--p0').trim();
  var p1 = style.getPropertyValue('--p1').trim();
  var p2 = style.getPropertyValue('--p2').trim();
  var ok = style.getPropertyValue('--ok').trim();
  var ink = style.getPropertyValue('--ink').trim();
  var muted = style.getPropertyValue('--muted').trim();
  var rule = style.getPropertyValue('--rule').trim();
  var bg2 = style.getPropertyValue('--bg2').trim();

  // --- Chart: issue distribution by severity (all closed) ---
  var statusEl = document.getElementById('chart-fix-status');
  if (statusEl && window.echarts) {
    var status = echarts.init(statusEl, null, { renderer: 'svg' });
    status.setOption({
      animation: false,
      color: [p0, p1, p2],
      tooltip: {
        trigger: 'item',
        appendToBody: true,
        formatter: '{b}：{c} 项（{d}%）'
      },
      legend: {
        bottom: 0,
        left: 'center',
        itemWidth: 10,
        itemHeight: 10,
        itemGap: 18,
        textStyle: { color: muted, fontSize: 12 }
      },
      series: [{
        type: 'pie',
        radius: ['48%', '70%'],
        center: ['50%', '44%'],
        itemStyle: { borderColor: bg2, borderWidth: 2 },
        label: {
          show: true,
          formatter: '{b}\n{c} 项',
          color: ink,
          fontSize: 12,
          lineHeight: 18,
          fontWeight: 600
        },
        labelLine: { length: 12, length2: 10, lineStyle: { color: rule } },
        data: [
          { value: 2, name: 'P0' },
          { value: 6, name: 'P1' },
          { value: 15, name: 'P2' }
        ]
      }]
    });
    window.addEventListener('resize', function () { status.resize(); });
  }

  // --- Chart: fixed count by severity ---
  var sevEl = document.getElementById('chart-fix-severity');
  if (sevEl && window.echarts) {
    var sev = echarts.init(sevEl, null, { renderer: 'svg' });
    sev.setOption({
      animation: false,
      grid: { left: 8, right: 40, top: 16, bottom: 8, containLabel: true },
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        appendToBody: true,
        formatter: '{b}：已修复 {c} 项'
      },
      xAxis: {
        type: 'value',
        minInterval: 1,
        axisLabel: { color: muted, fontSize: 12 },
        splitLine: { lineStyle: { color: rule, type: 'dashed' } },
        axisLine: { show: false }
      },
      yAxis: {
        type: 'category',
        data: ['P2', 'P1', 'P0'],
        axisLabel: { color: ink, fontSize: 12, fontWeight: 600 },
        axisTick: { show: false },
        axisLine: { lineStyle: { color: rule } }
      },
      series: [{
        name: '已修复',
        type: 'bar',
        barWidth: 18,
        data: [15, 6, 2],
        itemStyle: { color: ok, borderRadius: [0, 4, 4, 0] },
        label: { show: true, position: 'right', color: ok, fontSize: 12, fontWeight: 700, formatter: '{c} / {c}' }
      }]
    });
    // 用真实分母显示进度（P2 15、P1 6、P0 2）
    sev.setOption({
      series: [{
        label: {
          show: true,
          position: 'right',
          color: ok,
          fontSize: 12,
          fontWeight: 700,
          formatter: function (params) {
            var total = [15, 6, 2][params.dataIndex];
            return params.value + ' / ' + total;
          }
        }
      }]
    });
    window.addEventListener('resize', function () { sev.resize(); });
  }
})();
